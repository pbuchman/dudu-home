package com.pbuchman.duduhome.speed;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import com.pbuchman.duduhome.R;

/** Android's current default route and its validation evidence. */
public record SpeedConnection(Network network, Transport transport, boolean validated) {
    public enum Transport {
        WIFI, CELLULAR, ETHERNET, VPN, OTHER, NONE
    }

    public static SpeedConnection current(Context context) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        Network network = manager == null ? null : manager.getActiveNetwork();
        if (network == null) return new SpeedConnection(null, Transport.NONE, false);

        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        if (capabilities == null) return new SpeedConnection(network, Transport.OTHER, false);
        Transport transport = Transport.OTHER;
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            transport = Transport.VPN;
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            transport = Transport.WIFI;
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            transport = Transport.CELLULAR;
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            transport = Transport.ETHERNET;
        }
        return new SpeedConnection(network, transport,
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
    }

    public int label() {
        return switch (transport) {
            case WIFI -> R.string.speed_network_wifi;
            case CELLULAR -> R.string.speed_network_cellular;
            case ETHERNET -> R.string.speed_network_ethernet;
            case VPN -> R.string.speed_network_vpn;
            case OTHER -> R.string.speed_network_other;
            case NONE -> R.string.speed_network_offline;
        };
    }
}
