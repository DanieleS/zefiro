package com.limelight.stats;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * What it takes to talk to one paired host over HTTPS, small enough to travel in an Intent: the
 * statistics screen is its own Activity and has no ComputerManagerService binding of its own, so
 * the library hands it the address, ports, client id and pinned certificate it already holds,
 * the same way it hands them to the stream.
 */
public final class HostLink {
    private static final String EXTRA_ADDRESS = "HostLink.Address";
    private static final String EXTRA_PORT = "HostLink.Port";
    private static final String EXTRA_HTTPS_PORT = "HostLink.HttpsPort";
    private static final String EXTRA_UNIQUE_ID = "HostLink.UniqueId";
    private static final String EXTRA_CERT = "HostLink.Cert";
    private static final String EXTRA_PC_UUID = "HostLink.PcUuid";
    private static final String EXTRA_PC_NAME = "HostLink.PcName";

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

    public void putInto(Intent intent) {
        intent.putExtras(toBundle());
    }

    /**
     * The link as a bundle: an Intent's extras, or a fragment's arguments (the Stats section
     * hosted by the legacy statistics screen).
     */
    public Bundle toBundle() {
        Bundle out = new Bundle();
        out.putString(EXTRA_ADDRESS, address.address);
        out.putInt(EXTRA_PORT, address.port);
        out.putInt(EXTRA_HTTPS_PORT, httpsPort);
        out.putString(EXTRA_UNIQUE_ID, uniqueId);
        out.putString(EXTRA_PC_UUID, pcUuid);
        out.putString(EXTRA_PC_NAME, pcName);
        if (serverCert != null) {
            try {
                out.putByteArray(EXTRA_CERT, serverCert.getEncoded());
            } catch (CertificateEncodingException e) {
                // Without the pin the connection is refused, which the screen reports as a failure.
            }
        }
        return out;
    }

    /** The link an Intent carries, or null when it carries none. */
    public static HostLink from(Intent intent) {
        return from(intent.getExtras());
    }

    /** The link a bundle carries (see {@link #toBundle()}), or null when it carries none. */
    public static HostLink from(Bundle in) {
        if (in == null) {
            return null;
        }
        String host = in.getString(EXTRA_ADDRESS);
        String uniqueId = in.getString(EXTRA_UNIQUE_ID);
        if (host == null || uniqueId == null) {
            return null;
        }
        X509Certificate cert = null;
        byte[] der = in.getByteArray(EXTRA_CERT);
        if (der != null) {
            try {
                cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(der));
            } catch (Exception e) {
                cert = null;
            }
        }
        return new HostLink(
                new ComputerDetails.AddressTuple(host, in.getInt(EXTRA_PORT, NvHTTP.DEFAULT_HTTP_PORT)),
                in.getInt(EXTRA_HTTPS_PORT, 0),
                uniqueId, cert,
                in.getString(EXTRA_PC_UUID),
                in.getString(EXTRA_PC_NAME));
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
