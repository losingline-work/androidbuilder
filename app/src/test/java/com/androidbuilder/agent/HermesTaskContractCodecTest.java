package com.androidbuilder.agent;

import com.androidbuilder.model.HermesTaskContract;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HermesTaskContractCodecTest {
    @Test
    public void parsesOptionalTaskContractFieldsAndRoundTripsInstructionBlock() throws Exception {
        JSONObject json = new JSONObject("{"
                + "\"title\":\"Resources\","
                + "\"instruction\":\"Write resources.\","
                + "\"allowedPaths\":[\"app/src/main/res/values/strings.xml\"],"
                + "\"expectedFiles\":[\"app/src/main/res/values/colors.xml\"],"
                + "\"forbiddenPaths\":[\"app/src/main/java\"],"
                + "\"acceptanceChecks\":[\"No missing string references\"],"
                + "\"riskNotes\":[\"Resources must exist before layout XML.\"],"
                + "\"dependsOn\":[\"Gradle skeleton\"],"
                + "\"produces\":[\"base resources\"],"
                + "\"rollbackScope\":[\"res/values\"],"
                + "\"riskLevel\":\"medium\","
                + "\"buildRequiredAfter\":true"
                + "}");

        HermesTaskContract contract = HermesTaskContractCodec.fromJson(json);
        String encoded = HermesTaskContractCodec.appendToInstruction("Write resources.", contract);
        HermesTaskContract decoded = HermesTaskContractCodec.extractFromInstruction(encoded);

        assertEquals("medium", decoded.riskLevel);
        assertTrue(decoded.buildRequiredAfter);
        assertEquals("app/src/main/res/values/strings.xml", decoded.allowedPaths.get(0));
        assertEquals("app/src/main/res/values/colors.xml", decoded.expectedFiles.get(0));
        assertEquals("app/src/main/java", decoded.forbiddenPaths.get(0));
        assertEquals("No missing string references", decoded.acceptanceChecks.get(0));
        assertEquals("Resources must exist before layout XML.", decoded.riskNotes.get(0));
        assertEquals("Gradle skeleton", decoded.dependsOn.get(0));
        assertEquals("base resources", decoded.produces.get(0));
        assertEquals("res/values", decoded.rollbackScope.get(0));
    }

    @Test
    public void stripsRawContractBlockAndCreatesReadablePromptContext() throws Exception {
        JSONObject json = new JSONObject("{"
                + "\"allowedPaths\":[\"app/src/main/res/layout/activity_main.xml\"],"
                + "\"forbiddenPaths\":[\"app/src/main/java/com/example/Legacy.java\"],"
                + "\"acceptanceChecks\":[\"No missing IDs\"],"
                + "\"riskLevel\":\"high\""
                + "}");
        String encoded = HermesTaskContractCodec.appendToInstruction("Create main layout.", HermesTaskContractCodec.fromJson(json));
        String context = HermesTaskContractCodec.promptContextFromInstruction(encoded);

        assertEquals("Create main layout.", HermesTaskContractCodec.stripFromInstruction(encoded));
        assertTrue(context.contains("Hermes task contract"));
        assertTrue(context.contains("allowedPaths: app/src/main/res/layout/activity_main.xml"));
        assertTrue(context.contains("forbiddenPaths: app/src/main/java/com/example/Legacy.java"));
        assertTrue(context.contains("acceptanceChecks: No missing IDs"));
        assertTrue(context.contains("riskLevel: high"));
    }

    @Test
    public void emptyContractDoesNotAppendBlock() {
        String encoded = HermesTaskContractCodec.appendToInstruction("Plain instruction.", HermesTaskContract.empty());

        assertEquals("Plain instruction.", encoded);
        assertFalse(HermesTaskContractCodec.extractFromInstruction(encoded).hasSignals());
    }
}
