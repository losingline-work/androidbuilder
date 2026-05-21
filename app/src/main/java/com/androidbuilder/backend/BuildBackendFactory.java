package com.androidbuilder.backend;

import android.content.Context;

import com.androidbuilder.data.AppRepository;
import com.androidbuilder.server.LocalBuildServer;

public final class BuildBackendFactory {
    private BuildBackendFactory() {
    }

    public static BuildBackend create(Context context, AppRepository repository, LocalBuildServer localBuildServer) {
        String selected = BuildBackendSettings.selected(context);
        if (BuildBackendSettings.EXTERNAL_TERMUX.equals(selected)) {
            return new ExternalTermuxBackend(context, repository, localBuildServer);
        }
        return new EmbeddedRuntimeBackend(context, repository);
    }
}
