package com.limelight.grid.assets;

import android.content.Context;

import com.limelight.LimeLog;
import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.utils.ServerHelper;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

public class NetworkAssetLoader {
    private final Context context;
    private final String uniqueId;

    public NetworkAssetLoader(Context context, String uniqueId) {
        this.context = context;
        this.uniqueId = uniqueId;
    }

    /**
     * The cover's bytes as the host sends them, or null when the host could not be reached.
     *
     * @throws FileNotFoundException when the host answered that it has no cover for the app,
     *                               which is an answer, unlike a failure to reach it
     */
    public InputStream getBitmapStream(CachedAppAssetLoader.LoaderTuple tuple) throws FileNotFoundException {
        InputStream in = null;
        try {
            NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(tuple.computer),
                    tuple.computer.httpsPort, uniqueId, tuple.computer.serverCert,
                    PlatformBinding.getCryptoProvider(context));
            in = http.getBoxArt(tuple.app);
        } catch (FileNotFoundException e) {
            LimeLog.info("Host has no asset for: " + tuple);
            throw e;
        } catch (IOException ignored) {}

        if (in != null) {
            LimeLog.info("Network asset load complete: " + tuple);
        }
        else {
            LimeLog.info("Network asset load failed: " + tuple);
        }

        return in;
    }
}
