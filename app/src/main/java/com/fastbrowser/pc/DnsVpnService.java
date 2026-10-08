package com.fastbrowser.pc;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Browser-scoped DNS VPN. It captures only IPv4 DNS packets destined for the
 * configured DNS servers and forwards the DNS payload through a protected
 * socket, leaving ordinary web traffic on the device's normal network path.
 */
public class DnsVpnService extends VpnService {
    public static final String ACTION_START = "com.fastbrowser.pc.DNS_START";
    public static final String ACTION_STOP = "com.fastbrowser.pc.DNS_STOP";
    public static final String EXTRA_PRIMARY = "primary";
    public static final String EXTRA_SECONDARY = "secondary";

    private ParcelFileDescriptor vpnInterface;
    private Thread worker;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private String primary, secondary;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            primary = intent.getStringExtra(EXTRA_PRIMARY);
            secondary = intent.getStringExtra(EXTRA_SECONDARY);
            startVpn();
        }
        return START_STICKY;
    }

    private synchronized void startVpn() {
        stopVpn();
        if (!validIpv4(primary)) return;
        if (!validIpv4(secondary)) secondary = null;
        try {
            Builder b = new Builder()
                    .setSession("Fast Browser PC DNS")
                    .setMtu(1500)
                    .addAddress("10.10.10.2", 32)
                    .addDnsServer(primary)
                    .addRoute(primary, 32);
            if (secondary != null) {
                b.addDnsServer(secondary);
                b.addRoute(secondary, 32);
            }
            vpnInterface = b.establish();
            if (vpnInterface == null) return;
            running.set(true);
            worker = new Thread(this::loop, "FastBrowser-DNS");
            worker.start();
        } catch (Exception ignored) {
            stopVpn();
        }
    }

    private void loop() {
        byte[] packet = new byte[32767];
        try (FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor())) {
            while (running.get()) {
                int n = in.read(packet);
                if (n <= 0) continue;
                handlePacket(packet, n, out);
            }
        } catch (Exception ignored) {
            // Closing the VPN descriptor is the normal way to stop this loop.
        }
    }

    private void handlePacket(byte[] p, int n, FileOutputStream out) {
        if (n < 28 || (p[0] & 0xF0) != 0x40) return;
        int ihl = (p[0] & 0x0F) * 4;
        if (ihl < 20 || n < ihl + 8 || (p[9] & 0xFF) != 17) return;
        int udp = ihl;
        int srcPort = u16(p, udp);
        int dstPort = u16(p, udp + 2);
        int udpLen = u16(p, udp + 4);
        if (dstPort != 53 || udpLen < 8 || udp + udpLen > n) return;

        byte[] query = new byte[udpLen - 8];
        System.arraycopy(p, udp + 8, query, 0, query.length);
        byte[] answer = queryDns(query, primary);
        if (answer == null && secondary != null) answer = queryDns(query, secondary);
        if (answer == null) return;

        byte[] r = new byte[20 + 8 + answer.length];
        r[0] = 0x45;
        r[1] = 0;
        put16(r, 2, r.length);
        put16(r, 4, u16(p, 4));
        put16(r, 6, 0);
        r[8] = 64;
        r[9] = 17;
        // Response source is the DNS server address from the original packet.
        System.arraycopy(p, 16, r, 12, 4);
        System.arraycopy(p, 12, r, 16, 4);
        put16(r, 10, 0);
        put16(r, 10, checksum(r, 0, 20));

        put16(r, 20, 53);
        put16(r, 22, srcPort);
        put16(r, 24, 8 + answer.length);
        put16(r, 26, 0);
        System.arraycopy(answer, 0, r, 28, answer.length);
        int sum = udpChecksum(r, 12, 16, 20, 8 + answer.length);
        put16(r, 26, sum);
        try { out.write(r); out.flush(); } catch (Exception ignored) { }
    }

    private byte[] queryDns(byte[] query, String server) {
        if (!validIpv4(server)) return null;
        try (DatagramSocket s = new DatagramSocket()) {
            protect(s);
            s.setSoTimeout(2500);
            byte[] ip = InetAddress.getByName(server).getAddress();
            DatagramPacket q = new DatagramPacket(query, query.length, InetAddress.getByAddress(ip), 53);
            s.send(q);
            byte[] buf = new byte[8192];
            DatagramPacket a = new DatagramPacket(buf, buf.length);
            s.receive(a);
            byte[] result = new byte[a.getLength()];
            System.arraycopy(a.getData(), a.getOffset(), result, 0, a.getLength());
            return result;
        } catch (Exception e) { return null; }
    }

    private synchronized void stopVpn() {
        running.set(false);
        if (vpnInterface != null) { try { vpnInterface.close(); } catch (Exception ignored) {} vpnInterface = null; }
        worker = null;
    }

    @Override public void onDestroy() { stopVpn(); super.onDestroy(); }

    private static int u16(byte[] b, int o) { return ((b[o] & 255) << 8) | (b[o + 1] & 255); }
    private static void put16(byte[] b, int o, int v) { b[o]=(byte)(v>>>8); b[o+1]=(byte)v; }
    private static int checksum(byte[] b, int off, int len) {
        long sum=0;
        for(int i=off;i<off+len;i+=2){ int x=(b[i]&255)<<8; if(i+1<off+len)x|=b[i+1]&255; sum+=x; while((sum>>>16)!=0)sum=(sum&65535)+(sum>>>16); }
        return (int)(~sum)&65535;
    }
    private static int udpChecksum(byte[] b, int src, int pseudoLen, int udp, int len) {
        long sum=0;
        for(int i=src;i<src+8;i+=2){ sum += ((b[i]&255)<<8)|(b[i+1]&255); while((sum>>>16)!=0)sum=(sum&65535)+(sum>>>16); }
        sum += 17; sum += len;
        for(int i=udp;i<udp+len;i+=2){ int x=(b[i]&255)<<8; if(i+1<udp+len)x|=b[i+1]&255; sum+=x; while((sum>>>16)!=0)sum=(sum&65535)+(sum>>>16); }
        return (int)(~sum)&65535;
    }
    private static boolean validIpv4(String s) {
        if(s==null)return false;
        String[] a=s.trim().split("\\."); if(a.length!=4)return false;
        try { for(String x:a){ int v=Integer.parseInt(x); if(v<0||v>255)return false; } return true; } catch(Exception e){return false;}
    }
}
