package com.limelight.stats;

import android.content.Context;

import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;

import java.io.IOException;
import java.security.cert.X509Certificate;

/**
 * What it takes to talk to one paired host over HTTPS: the address, ports, client id and pinned
 * certificate a polled host already carries. It is cut from a {@link ComputerDetails} snapshot so
 * a {@link HostSession} can keep talking to that host without holding the computer manager, the
 * way the stream is handed the same details.
 */
public final class HostLink {
    private final ComputerDetails.AddressTuple address;
    private final int httpsPort;
    private final String uniqueId;
    private final X509Certificate serverCert;
    private final String pcUuid;
    private final String pcName;

    public HostLink(ComputerDetails.AddressTuple address, int httpsPort, String uniqueId,
                    X509Certificate serverCert, String pcUuid, String pcName) {
        this.address = address;
        this.httpsPort = httpsPort;
        this.uniqueId = uniqueId;
        this.serverCert = serverCert;
        this.pcUuid = pcUuid;
        this.pcName = pcName;
    }

    /** The link to a computer the library is showing, or null while it has no address. */
    public static HostLink of(ComputerDetails computer, String uniqueId) {
        if (computer == null || computer.activeAddress == null || uniqueId == null) {
            return null;
        }
        return new HostLink(computer.activeAddress, computer.httpsPort, uniqueId,
                computer.serverCert, computer.uuid, computer.name);
    }

    /** A fresh connection; call off the main thread, as every request on it blocks. */
    public NvHTTP open(Context context) throws IOException {
        return new NvHTTP(address, httpsPort, uniqueId, serverCert, PlatformBinding.getCryptoProvider(context));
    }

    public String getPcUuid() {
        return pcUuid;
    }

    public String getPcName() {
        return pcName;
    }
}
