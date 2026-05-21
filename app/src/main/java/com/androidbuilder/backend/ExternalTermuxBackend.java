package com.androidbuilder.backend;

import android.content.Context;

import com.androidbuilder.data.AppRepository;
import com.androidbuilder.model.BuildJobRecord;
import com.androidbuilder.server.LocalBuildServer;
import com.androidbuilder.termux.TermuxBridge;

public class ExternalTermuxBackend implements BuildBackend {
    private final Context context;
    private final AppRepository repository;
    private final LocalBuildServer localBuildServer;

    public ExternalTermuxBackend(Context context, AppRepository repository, LocalBuildServer localBuildServer) {
        this.context = context;
        this.repository = repository;
        this.localBuildServer = localBuildServer;
    }

    @Override
    public String id() {
        return BuildBackendSettings.EXTERNAL_TERMUX;
    }

    @Override
    public void build(BuildJobRecord job, Listener listener) {
        repository.updateBuildJob(job.id, "building", "external_termux_start", job.logsPath, job.apkPath, null, job.retryCount);
        new TermuxBridge(context).build(job.projectId, job.id, localBuildServer.callbackUrl(), localBuildServer.token());
        listener.onJobChanged(job.projectId, job.id);
    }
}
