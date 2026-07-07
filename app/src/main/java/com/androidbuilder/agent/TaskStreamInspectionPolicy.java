package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.HermesTaskContract;
import com.androidbuilder.model.TaskOperations;

import java.util.ArrayList;
import java.util.List;

final class TaskStreamInspectionPolicy implements OpenAiClient.StreamInspector {
    static final int REVIEW_INTERVAL_CHARS = 8_000;

    private final HermesTaskContract contract;
    private String latestAnswer = "";
    private int lastReviewedLength = 0;
    private int lastReviewedOperationCount = -1;

    TaskStreamInspectionPolicy(HermesTaskContract contract) {
        this.contract = contract == null ? HermesTaskContract.empty() : contract;
    }

    @Override
    public void onContent(String answerSoFar) throws OpenAiClient.StreamAbortException {
        latestAnswer = answerSoFar == null ? "" : answerSoFar;
        if (StreamFusePolicy.exceeds(latestAnswer.length())) {
            throw new OpenAiClient.StreamAbortException(StreamFusePolicy.fuseError(latestAnswer.length()));
        }
        List<FileOperation> operations = TaskOperationsCodec.completedOperations(latestAnswer);
        if (!shouldReview(operations.size())) {
            return;
        }
        String fatalError = TaskStreamPreflight.review(operations, contract);
        lastReviewedLength = latestAnswer.length();
        lastReviewedOperationCount = operations.size();
        if (fatalError != null && !fatalError.trim().isEmpty()) {
            throw new OpenAiClient.StreamAbortException(fatalError.trim());
        }
    }

    TaskOperations partialDraft(String summary) {
        // Drafts may only hold materialized full operations: a salvaged edit has no base draft
        // to apply to, and a later correction merge rejects edits found in the previous draft.
        List<FileOperation> salvaged = new ArrayList<>();
        for (FileOperation operation : TaskOperationsCodec.completedOperations(latestAnswer)) {
            if (!"edit".equals(operation.action)) {
                salvaged.add(operation);
            }
        }
        return CanonicalPathPolicy.canonicalizeAll(new TaskOperations(summary, salvaged));
    }

    private boolean shouldReview(int operationCount) {
        return operationCount != lastReviewedOperationCount
                || latestAnswer.length() - lastReviewedLength >= REVIEW_INTERVAL_CHARS;
    }
}
