package com.androidbuilder.model;

import java.util.Collections;
import java.util.List;

public class ContextNegotiation {
    public final boolean ready;
    public final List<String> neededFiles;
    public final List<String> focusTerms;
    public final List<String> riskNotes;
    public final String patchIntent;

    public ContextNegotiation(boolean ready, List<String> neededFiles, List<String> focusTerms, List<String> riskNotes, String patchIntent) {
        this.ready = ready;
        this.neededFiles = Collections.unmodifiableList(neededFiles);
        this.focusTerms = Collections.unmodifiableList(focusTerms);
        this.riskNotes = Collections.unmodifiableList(riskNotes);
        this.patchIntent = patchIntent == null ? "" : patchIntent;
    }
}
