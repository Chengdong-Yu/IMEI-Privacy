package com.richard.imeiprivacy;

import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.immutable.ImmutableClassDef;
import org.jf.dexlib2.immutable.ImmutableDexFile;
import org.jf.dexlib2.immutable.ImmutableField;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.immutable.ImmutableMethodImplementation;
import org.jf.dexlib2.immutable.ImmutableMethodParameter;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.ImmutableFieldReference;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.jf.dexlib2.immutable.reference.ImmutableStringReference;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class DexSemanticDiscoveryTest {
    private static final String STR = "Ljava/lang/String;";
    private static final String CTX = "Landroid/content/Context;";
    private static final String MAP = "Ljava/util/Map;";
    private static final String OBJ = "Ljava/lang/Object;";
    private static final int PUBLIC = AccessFlags.PUBLIC.getValue();
    private static final int STATIC = AccessFlags.STATIC.getValue();
    private static final int ABSTRACT = AccessFlags.ABSTRACT.getValue();

    @Test public void discoversFullyRenamedFieldsAndReadersAcrossBranches() {
        DexHookPlan plan = scan(info("Lfuture/Randomized;", true, false, true));
        assertEquals(1, plan.infos.size());
        DexHookPlan.Info info = plan.infos.get(0);
        assertEquals("unique_sn", info.fields.get(DexHookPlan.Role.SERIAL));
        assertEquals("radio_mac", info.fields.get(DexHookPlan.Role.MAC));
        assertTrue(info.readers.get(DexHookPlan.Role.CORE_ID).stream().anyMatch(m -> m.name.equals("read_xyz")));
    }

    @Test public void fieldLoadOrderDoesNotDetermineFormatArgumentOrder() {
        DexHookPlan plan = scan(info("Lrenamed/Other;", true, false, false));
        assertEquals(1, plan.infos.size());
        assertEquals("stable_id", plan.infos.get(0).fields.get(DexHookPlan.Role.DEVICE_ID));
        assertEquals("unique_sn", plan.infos.get(0).fields.get(DexHookPlan.Role.SERIAL));
    }

    @Test public void declarationShapeAloneIsNotADeviceInfoMatch() {
        assertEquals(0, scan(info("Ldecoy/SameShape;", false, false, false)).infos.size());
    }

    @Test public void duplicateFieldBindingIsRejected() {
        assertEquals(0, scan(info("Ldecoy/Ambiguous;", true, true, false)).infos.size());
    }

    @Test public void fidDataFlowDoesNotSelectOwnerOrHostGetters() {
        DexHookPlan plan = scan(credentialContract(true), credentialImpl(), fidRequest("cloudsp_fid"));
        assertEquals(1, plan.credentials.size());
        assertEquals("random_fid", plan.credentials.iterator().next().name);
        assertTrue(plan.credentials.stream().noneMatch(m -> m.name.equals("random_owner")));
    }

    @Test public void accountMapKeyIsNotEvidenceOfAFidGetter() {
        assertEquals(0, scan(credentialContract(true), credentialImpl(), fidRequest("cloudsp_userId")).credentials.size());
    }

    @Test public void stringGetterWithoutSigningContractIsRejected() {
        assertEquals(0, scan(credentialContract(false), credentialImpl(), fidRequest("cloudsp_fid")).credentials.size());
    }

    @Test public void multidexResolutionIsIndependentOfDexOrder() {
        DexHookPlan plan = DexSemanticDiscovery.scanDex(Arrays.asList(
                new ImmutableDexFile(Opcodes.getDefault(), Arrays.asList(credentialImpl(), fidRequest("cloudsp_fid"))),
                new ImmutableDexFile(Opcodes.getDefault(), Collections.singletonList(credentialContract(true)))));
        assertEquals(2, plan.dexFiles);
        assertEquals(1, plan.credentials.size());
    }

    @Test public void renamedHttpWrappersRequireDelegationAndRealHttpWorker() {
        DexHookPlan plan = scan(transport(), httpWorker());
        assertEquals(2, plan.transports.size());
        assertTrue(plan.transports.stream().anyMatch(m -> m.name.equals("totally_renamed_sender")));
        assertTrue(plan.transports.stream().anyMatch(m -> m.name.equals("totally_renamed_wrapper")));
        assertFalse(plan.transports.stream().anyMatch(m -> m.name.equals("unrelated_same_signature")));
        assertEquals(0, scan(transport()).transports.size());
    }

    @Test public void requestAndCloudProviderAreDiscoveredWithoutClassNames() {
        ClassDef provider = type("Lfuture/Provider;", "Landroid/content/ContentProvider;", Collections.emptyList(),
                Collections.emptyList(), Collections.singletonList(method("Lfuture/Provider;", "call",
                        Arrays.asList(STR, STR, "Landroid/os/Bundle;"), "Landroid/os/Bundle;", PUBLIC,
                        text(0, "getCloudId"), text(0, "result_id"), new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))));
        DexHookPlan plan = scan(fidRequest("cloudsp_fid"), provider);
        assertEquals(1, plan.requests.size());
        assertEquals(1, plan.cloudProviders.size());
    }

    @Test public void identicalLabelsInANonProviderDoNotHookItsBundleMethod() {
        ClassDef decoy = type("Ldecoy/BundleHelper;", OBJ, Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(method("Ldecoy/BundleHelper;", "call", Arrays.asList(STR, STR, "Landroid/os/Bundle;"),
                        "Landroid/os/Bundle;", PUBLIC, text(0, "getCloudId"), text(0, "result_id"))));
        assertEquals(0, scan(decoy).cloudProviders.size());
    }

    @Test public void protocolLabelsCanBeReordered() {
        assertEquals(Arrays.asList(DexHookPlan.Role.MAC, DexHookPlan.Role.SERIAL, DexHookPlan.Role.DEVICE_ID),
                DexSemanticDiscovery.labels("MAC: %s, SN: %s, deviceid: %s"));
    }

    private static DexHookPlan scan(ClassDef... classes) {
        return DexSemanticDiscovery.scanDex(Collections.singletonList(
                new ImmutableDexFile(Opcodes.getDefault(), Arrays.asList(classes))));
    }

    private static ClassDef info(String owner, boolean labels, boolean ambiguous, boolean branch) {
        String[] names = {"stable_id", "core_value", "unique_sn", "radio_mac", "model_name"};
        List<ImmutableField> fields = new ArrayList<>();
        for (String name : names) fields.add(new ImmutableField(owner, name, STR, PUBLIC, null, Collections.emptySet(), Collections.emptySet()));
        List<Instruction> describe = new ArrayList<>();
        // Load in reverse order, but the filled array preserves semantic order.
        for (int index = 4; index >= 0; index--) {
            describe.add(new ImmutableInstruction22c(Opcode.IGET_OBJECT, index + 1, 7,
                    new ImmutableFieldReference(owner, names[index], STR)));
        }
        describe.add(new ImmutableInstruction35c(Opcode.FILLED_NEW_ARRAY, 5,
                1, 2, ambiguous ? 1 : 3, 4, 5, new org.jf.dexlib2.immutable.reference.ImmutableTypeReference("[Ljava/lang/Object;")));
        describe.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 6));
        describe.add(text(0, labels ? "deviceid: %s, IMEI: %s, SN: %s, MAC: %s, model: %s" : "%s %s %s %s %s"));
        describe.add(invoke(Opcode.INVOKE_STATIC, "Ljava/lang/String;", "format", Arrays.asList(STR, "[Ljava/lang/Object;"), STR, 0, 6));
        describe.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        describe.add(new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0));
        List<Instruction> construct = new ArrayList<>();
        construct.add(invoke(Opcode.INVOKE_STATIC, owner, "read_xyz", Collections.singletonList(CTX), STR, 0));
        construct.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1));
        if (branch) {
            construct.add(new ImmutableInstruction21t(Opcode.IF_EQZ, 0, 5));
            construct.add(text(1, ""));
            construct.add(new ImmutableInstruction10t(Opcode.GOTO, 1));
        }
        construct.add(new ImmutableInstruction22c(Opcode.IPUT_OBJECT, 1, 2,
                new ImmutableFieldReference(owner, "core_value", STR)));
        construct.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
        return type(owner, OBJ, Collections.emptyList(), fields, Arrays.asList(
                method(owner, "<init>", Arrays.asList(CTX, "Z"), "V", PUBLIC | AccessFlags.CONSTRUCTOR.getValue(), construct.toArray(new Instruction[0])),
                method(owner, "toString", Collections.emptyList(), STR, PUBLIC, describe.toArray(new Instruction[0])),
                method(owner, "cache_different_name", Collections.singletonList(CTX), owner, PUBLIC | STATIC,
                        new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
                method(owner, "read_xyz", Collections.singletonList(CTX), STR, PUBLIC | STATIC,
                        text(0, "fixture"), new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))));
    }

    private static ClassDef credentialContract(boolean sign) {
        List<Method> methods = new ArrayList<>();
        methods.add(method("Lfuture/Contract;", "random_fid", Collections.emptyList(), STR, PUBLIC | ABSTRACT, (Instruction[]) null));
        if (sign) methods.add(method("Lfuture/Contract;", "random_sign", Collections.singletonList("[B"), "[B", PUBLIC | ABSTRACT, (Instruction[]) null));
        return type("Lfuture/Contract;", OBJ, Collections.emptyList(), Collections.emptyList(), methods);
    }

    private static ClassDef credentialImpl() {
        return type("Lfuture/Impl;", "Lfuture/Contract;", Collections.emptyList(), Collections.emptyList(), Arrays.asList(
                method("Lfuture/Impl;", "random_fid", Collections.emptyList(), STR, PUBLIC, text(0, "fixture-fid")),
                method("Lfuture/Impl;", "random_owner", Collections.emptyList(), STR, PUBLIC, text(0, "fixture-account"))));
    }

    private static ClassDef fidRequest(String key) {
        return type("Lfuture/Requests;", OBJ, Collections.emptyList(), Collections.emptyList(), Collections.singletonList(
                method("Lfuture/Requests;", "unknown_name", Arrays.asList(MAP, MAP), "Lorg/json/JSONObject;", PUBLIC,
                        invoke(Opcode.INVOKE_VIRTUAL, "Lfuture/Contract;", "random_fid", Collections.emptyList(), STR, 0),
                        new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1), text(2, key),
                        invoke(Opcode.INVOKE_INTERFACE, MAP, "put", Arrays.asList(OBJ, OBJ), OBJ, 3, 2, 1),
                        new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))));
    }

    private static ClassDef transport() {
        List<String> sender = Arrays.asList(STR, STR, MAP, OBJ);
        return type("Lfuture/Transport;", OBJ, Collections.emptyList(), Collections.emptyList(), Arrays.asList(
                method("Lfuture/Transport;", "totally_renamed_sender", sender, "V", PUBLIC, new ImmutableInstruction10x(Opcode.RETURN_VOID)),
                method("Lfuture/Transport;", "totally_renamed_wrapper", Arrays.asList(STR, MAP, OBJ), "V", PUBLIC,
                        invoke(Opcode.INVOKE_VIRTUAL, "Lfuture/Transport;", "totally_renamed_sender", sender, "V", 0, 1, 2, 3, 4),
                        new ImmutableInstruction10x(Opcode.RETURN_VOID)),
                method("Lfuture/Transport;", "unrelated_same_signature", Arrays.asList(STR, MAP, OBJ), "V", PUBLIC,
                        new ImmutableInstruction10x(Opcode.RETURN_VOID))));
    }

    private static ClassDef httpWorker() {
        return type("Lfuture/Worker;", OBJ, Collections.emptyList(),
                Collections.singletonList(new ImmutableField("Lfuture/Worker;", "random_parent", "Lfuture/Transport;", PUBLIC, null, Collections.emptySet(), Collections.emptySet())),
                Collections.singletonList(method("Lfuture/Worker;", "execute", Collections.emptyList(), "V", PUBLIC,
                        invoke(Opcode.INVOKE_VIRTUAL, "Ljava/net/URL;", "openConnection", Collections.emptyList(), "Ljava/net/URLConnection;", 0),
                        invoke(Opcode.INVOKE_VIRTUAL, "Ljava/net/HttpURLConnection;", "setRequestMethod", Collections.singletonList(STR), "V", 0, 1))));
    }

    private static ClassDef type(String owner, String parent, List<String> interfaces,
            List<? extends ImmutableField> fields, List<? extends Method> methods) {
        return new ImmutableClassDef(owner, PUBLIC, parent, interfaces, null, null, fields, methods);
    }

    private static Method method(String owner, String name, List<String> params, String result, int flags, Instruction... code) {
        List<ImmutableMethodParameter> parameters = new ArrayList<>();
        for (String parameter : params) parameters.add(new ImmutableMethodParameter(parameter, Collections.emptySet(), null));
        return new ImmutableMethod(owner, name, parameters, result, flags, null, null,
                code == null ? null : new ImmutableMethodImplementation(12, Arrays.asList(code), null, null));
    }

    private static Instruction text(int register, String value) {
        return new ImmutableInstruction21c(Opcode.CONST_STRING, register, new ImmutableStringReference(value));
    }

    private static Instruction invoke(Opcode opcode, String owner, String name, List<String> params, String result, int... args) {
        int[] registers = Arrays.copyOf(args, 5);
        return new ImmutableInstruction35c(opcode, args.length, registers[0], registers[1], registers[2], registers[3], registers[4],
                new ImmutableMethodReference(owner, name, params, result));
    }
}
