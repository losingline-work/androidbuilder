package com.androidbuilder.backend;

import com.androidbuilder.model.BuildJobRecord;

public interface BuildBackend {
    interface Listener {
        void onJobChanged(long projectId, long jobId);
    }

    String id();

    void build(BuildJobRecord job, Listener listener);
}
