package local.utena.mic;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

final class LanConnection {
    private static final int PORT = 48395;
    private static final String DISCOVERY = "UTENA_MIC_V2";
    private final ConnectivityManager connectivity;

    LanConnection(Context context) {
        connectivity = context.getSystemService(ConnectivityManager.class);
    }

    Socket open(String host, BooleanSupplier active) throws IOException {
        List<Network> links = new ArrayList<>();
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                links.add(network);
            }
        }
        if (!host.isEmpty()) {
            InetAddress address = InetAddress.getByName(host);
            for (Network link : links) {
                LinkProperties properties = connectivity.getLinkProperties(link);
                if (properties != null && properties.getLinkAddresses().stream().anyMatch(a -> sameSubnet(a, address)))
                    return connect(link, address);
            }
            return connect(null, address);
        }

        IOException failure = null;
        for (Network link : links) {
            try {
                InetAddress address = discover(link, active);
                if (address != null && active.getAsBoolean()) return connect(link, address);
            } catch (IOException error) {
                failure = error;
            }
        }
        if (!active.getAsBoolean()) throw new IOException("Conexión cancelada.");
        if (links.isEmpty()) {
            InetAddress address = discover(null, active);
            if (address != null && active.getAsBoolean()) return connect(null, address);
        }
        if (failure != null) throw failure;
        try {
            return connect(null, InetAddress.getByName("127.0.0.1"));
        } catch (IOException ignored) {
            throw new IOException("No encontré el receptor. Comprueba el Wi-Fi y prueba la IP que muestra Windows.");
        }
    }

    private static Socket connect(Network link, InetAddress address) throws IOException {
        Socket socket = link == null ? new Socket() : link.getSocketFactory().createSocket();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(address, PORT), 3000);
            return socket;
        } catch (IOException error) {
            socket.close();
            String detail = error instanceof SocketTimeoutException
                    ? "Tiempo de espera agotado. Comprueba el firewall y el aislamiento de la red Wi-Fi."
                    : "La PC rechazó la conexión o no está accesible. Pulsa Conectar en Windows y comprueba la IP.";
            throw new IOException(address.getHostAddress() + " · " + detail, error);
        }
    }

    private static boolean sameSubnet(LinkAddress local, InetAddress remote) {
        byte[] source = local.getAddress().getAddress(), target = remote.getAddress();
        int remaining = local.getPrefixLength();
        if (source.length != target.length || remaining == 0) return false;
        for (int i = 0; remaining > 0; i++, remaining -= 8) {
            int mask = 255 << Math.max(0, 8 - remaining);
            if (((source[i] ^ target[i]) & mask & 255) != 0) return false;
        }
        return true;
    }

    private InetAddress discover(Network link, BooleanSupplier active) throws IOException {
        try (DatagramSocket udp = new DatagramSocket(null)) {
            if (link != null) link.bindSocket(udp);
            udp.bind(new InetSocketAddress(0));
            udp.setBroadcast(true);
            Set<InetAddress> targets = new LinkedHashSet<>();
            LinkProperties properties = link == null ? null : connectivity.getLinkProperties(link);
            if (properties != null) {
                for (LinkAddress item : properties.getLinkAddresses()) {
                    byte[] ipv4 = item.getAddress().getAddress();
                    int prefix = item.getPrefixLength();
                    if (ipv4.length != 4 || prefix <= 0 || prefix >= 31) continue;
                    for (int bit = prefix; bit < 32; bit++)
                        ipv4[bit / 8] |= 1 << (7 - bit % 8);
                    targets.add(InetAddress.getByAddress(ipv4));
                }
            } else {
                java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                while (interfaces != null && interfaces.hasMoreElements()) {
                    NetworkInterface nic = interfaces.nextElement();
                    if (!nic.isUp() || nic.isLoopback()) continue;
                    for (InterfaceAddress item : nic.getInterfaceAddresses())
                        if (item.getBroadcast() != null) targets.add(item.getBroadcast());
                }
            }
            targets.add(InetAddress.getByName("255.255.255.255"));

            byte[] hello = (DISCOVERY + "?").getBytes(StandardCharsets.US_ASCII);
            byte[] replyBytes = new byte[32];
            Set<InetAddress> found = new LinkedHashSet<>();
            for (int attempt = 0; attempt < 2 && active.getAsBoolean() && found.isEmpty(); attempt++) {
                for (InetAddress address : targets) {
                    try { udp.send(new DatagramPacket(hello, hello.length, address, PORT)); }
                    catch (IOException ignored) { }
                }
                long cutoff = SystemClock.elapsedRealtime() + 550;
                while (active.getAsBoolean()) {
                    long remaining = cutoff - SystemClock.elapsedRealtime();
                    if (remaining <= 0) break;
                    udp.setSoTimeout((int) remaining);
                    DatagramPacket reply = new DatagramPacket(replyBytes, replyBytes.length);
                    try {
                        udp.receive(reply);
                    } catch (SocketTimeoutException ignored) {
                        break;
                    }
                    String message = new String(replyBytes, 0, reply.getLength(), StandardCharsets.US_ASCII);
                    if (reply.getPort() == PORT && (DISCOVERY + "!").equals(message))
                        found.add(reply.getAddress());
                }
            }
            if (found.size() > 1) throw new IOException("Hay varios receptores. Escribe la IP del que quieres usar.");
            return found.isEmpty() ? null : found.iterator().next();
        }
    }
}
