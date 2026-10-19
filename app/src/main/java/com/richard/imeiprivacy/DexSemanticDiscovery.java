package com.richard.imeiprivacy;

import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Field;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.MultiDexContainer;
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction;
import org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction;
import org.jf.dexlib2.iface.instruction.ThreeRegisterInstruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction;
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction;
import org.jf.dexlib2.iface.reference.FieldReference;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.iface.reference.Reference;
import org.jf.dexlib2.iface.reference.StringReference;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Name/version-independent discovery. Protocol keys, Java API types, diagnostic
 * labels and register data flow establish meaning; class/field order never does.
 * Unknown or ambiguous semantics are rejected instead of guessed.
 */
final class DexSemanticDiscovery {
    private static final String STRING = "Ljava/lang/String;";
    private static final String CONTEXT = "Landroid/content/Context;";
    private static final String BUNDLE = "Landroid/os/Bundle;";
    private static final String JSON = "Lorg/json/JSONObject;";
    private final Map<String, ClassDef> classes = new LinkedHashMap<>();
    private final Set<DexHookPlan.MethodId> fidCalls = new LinkedHashSet<>();
    private final Set<String> httpWorkers = new LinkedHashSet<>();
    private final DexHookPlan plan = new DexHookPlan();

    static DexHookPlan scan(Collection<File> apks) throws IOException {
        DexSemanticDiscovery scanner = new DexSemanticDiscovery();
        for (File apk : apks) {
            MultiDexContainer<? extends DexFile> container =
                    DexFileFactory.loadDexContainer(apk, Opcodes.getDefault());
            for (String entry : container.getDexEntryNames()) {
                MultiDexContainer.DexEntry<? extends DexFile> dex = container.getEntry(entry);
                if (dex != null) scanner.add(dex.getDexFile());
            }
        }
        return scanner.discover();
    }

    // In-memory DEX fixtures exercise the exact same discovery as real APKs.
    static DexHookPlan scanDex(Collection<? extends DexFile> dexFiles) {
        DexSemanticDiscovery scanner = new DexSemanticDiscovery();
        for (DexFile dex : dexFiles) scanner.add(dex);
        return scanner.discover();
    }

    private void add(DexFile dex) {
        plan.dexFiles++;
        for (ClassDef type : dex.getClasses()) {
            if (classes.putIfAbsent(type.getType(), type) != null) plan.rejected++;
        }
    }

    private DexHookPlan discover() {
        plan.classes = classes.size();
        for (ClassDef type : classes.values()) inspect(type);
        discoverCredentials();
        discoverTransports();
        return plan;
    }

    private void inspect(ClassDef type) {
        boolean cloudRequest = false;
        List<Method> mapMethods = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (mapCount(method) == 2 && JSON.equals(method.getReturnType())) mapMethods.add(method);
            MethodImplementation body = method.getImplementation();
            if (body == null) continue;
            Set<String> strings = new LinkedHashSet<>();
            boolean opensUrl = false;
            boolean setsHttpMethod = false;
            boolean preferenceRead = false;
            Map<Integer, Object> registers = new HashMap<>();
            Object pending = null;
            for (Instruction instruction : body.getInstructions()) {
                Reference reference = reference(instruction);
                if (reference instanceof StringReference) {
                    String value = ((StringReference) reference).getString();
                    strings.add(value);
                    if (value.startsWith("cloudsp_")) cloudRequest = true;
                }
                if (reference instanceof MethodReference) {
                    MethodReference call = (MethodReference) reference;
                    if ("Ljava/net/URL;".equals(call.getDefiningClass())
                            && "openConnection".equals(call.getName())) opensUrl = true;
                    if ("setRequestMethod".equals(call.getName())
                            && call.getDefiningClass().endsWith("HttpURLConnection;")) setsHttpMethod = true;
                    if ("Landroid/content/SharedPreferences;".equals(call.getDefiningClass())
                            && "getString".equals(call.getName())) preferenceRead = true;
                    int[] args = arguments(instruction);
                    if ("put".equals(call.getName()) && isMap(call.getDefiningClass()) && args.length == 3) {
                        Object key = registers.get(args[1]);
                        Object value = registers.get(args[2]);
                        if (("cloudsp_fid".equals(key) || "cloudsp_tzid".equals(key))
                                && value instanceof DexHookPlan.MethodId) {
                            DexHookPlan.MethodId source = (DexHookPlan.MethodId) value;
                            if (STRING.equals(source.result) && source.parameters.isEmpty()) fidCalls.add(source);
                        }
                    }
                }
                pending = track(instruction, reference, registers, pending);
            }
            if (opensUrl && setsHttpMethod) httpWorkers.add(type.getType());
            if (preferenceRead && strings.contains("device_fid")
                    && STRING.equals(method.getReturnType())
                    && parameters(method).equals(Collections.singletonList(CONTEXT))) {
                plan.fidCaches.add(id(method));
            }
            if (strings.contains("getCloudId") && strings.contains("result_id")
                    && BUNDLE.equals(method.getReturnType())
                    && parameters(method).equals(Arrays.asList(STRING, STRING, BUNDLE))
                    && descends(type.getType(), "Landroid/content/ContentProvider;")) {
                plan.cloudProviders.add(id(method));
            }
            if ("toString".equals(method.getName()) && STRING.equals(method.getReturnType())
                    && method.getParameterTypes().isEmpty()) {
                EnumMap<DexHookPlan.Role, String> fields = infoFields(type, method, strings);
                if (fields != null) discoverInfo(type, fields);
            }
        }
        if (cloudRequest) for (Method method : mapMethods) plan.requests.add(id(method));
    }

    private EnumMap<DexHookPlan.Role, String> infoFields(ClassDef type, Method method, Set<String> strings) {
        String text = String.join(" ", strings).toLowerCase(Locale.ROOT);
        if (!text.contains("deviceid:") || !text.contains("sn:") || !text.contains("mac:")
                || !text.contains("model:") || !(text.contains("imem:") || text.contains("imei:"))) return null;
        Map<String, Integer> declared = new HashMap<>();
        for (Field field : type.getInstanceFields()) {
            if (STRING.equals(field.getType())) declared.put(field.getName(), field.getAccessFlags());
        }
        EnumMap<DexHookPlan.Role, String> mapped = new EnumMap<>(DexHookPlan.Role.class);
        // Optimizers may load fields BEFORE the format string and reorder the
        // loads. Bind format placeholders to actual array registers, not order.
        EnumMap<DexHookPlan.Role, String> formatted = formattedFields(type, method, declared.keySet());
        if (formatted != null) return formatted;
        List<DexHookPlan.Role> order = new ArrayList<>();
        Set<String> seenFields = new HashSet<>();
        for (Instruction instruction : method.getImplementation().getInstructions()) {
            Reference ref = reference(instruction);
            if (ref instanceof StringReference) order.addAll(labels(((StringReference) ref).getString()));
            if (instruction.getOpcode() == Opcode.IGET_OBJECT && ref instanceof FieldReference) {
                FieldReference field = (FieldReference) ref;
                if (!type.getType().equals(field.getDefiningClass()) || !STRING.equals(field.getType())) continue;
                if (order.isEmpty() || !seenFields.add(field.getName()) || !declared.containsKey(field.getName())) {
                    plan.rejected++;
                    return null;
                }
                DexHookPlan.Role role = order.remove(0);
                if (mapped.put(role, field.getName()) != null) return null;
            }
        }
        if (mapped.size() != DexHookPlan.Role.values().length || !order.isEmpty()) {
            plan.rejected++;
            return null;
        }
        return mapped;
    }

    private EnumMap<DexHookPlan.Role, String> formattedFields(ClassDef type, Method method, Set<String> fields) {
        Map<Integer, Object> values = new HashMap<>();
        Object pending = null;
        for (Instruction instruction : method.getImplementation().getInstructions()) {
            Opcode opcode = instruction.getOpcode();
            Reference ref = reference(instruction);
            if (opcode == Opcode.MOVE_RESULT_OBJECT) {
                values.put(((OneRegisterInstruction) instruction).getRegisterA(), pending);
                pending = null;
            } else if (opcode == Opcode.IGET_OBJECT && ref instanceof FieldReference) {
                FieldReference field = (FieldReference) ref;
                values.put(((OneRegisterInstruction) instruction).getRegisterA(),
                        type.getType().equals(field.getDefiningClass()) && fields.contains(field.getName())
                                ? field : null);
            } else if (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) {
                values.put(((OneRegisterInstruction) instruction).getRegisterA(), ((StringReference) ref).getString());
            } else if (opcode == Opcode.FILLED_NEW_ARRAY || opcode == Opcode.FILLED_NEW_ARRAY_RANGE) {
                List<Object> array = new ArrayList<>();
                for (int arg : arguments(instruction)) array.add(values.get(arg));
                pending = array;
            } else if (opcode == Opcode.NEW_ARRAY) {
                TwoRegisterInstruction array = (TwoRegisterInstruction) instruction;
                Object length = values.get(array.getRegisterB());
                values.put(array.getRegisterA(), length instanceof Integer && (Integer) length >= 0 && (Integer) length <= 64
                        ? new ArrayList<>(Collections.nCopies((Integer) length, null)) : null);
            } else if (opcode == Opcode.APUT_OBJECT) {
                ThreeRegisterInstruction put = (ThreeRegisterInstruction) instruction;
                Object array = values.get(put.getRegisterB());
                Object index = values.get(put.getRegisterC());
                if (array instanceof List && index instanceof Integer && (Integer) index >= 0
                        && (Integer) index < ((List<?>) array).size()) {
                    @SuppressWarnings("unchecked") List<Object> elements = (List<Object>) array;
                    elements.set((Integer) index, values.get(put.getRegisterA()));
                }
            } else if (ref instanceof MethodReference && opcode.name().startsWith("INVOKE_")) {
                MethodReference call = (MethodReference) ref;
                if ("Ljava/lang/String;".equals(call.getDefiningClass()) && "format".equals(call.getName())) {
                    int[] args = arguments(instruction);
                    if (args.length >= 2) {
                        Object format = values.get(args[args.length - 2]);
                        Object array = values.get(args[args.length - 1]);
                        if (format instanceof String && array instanceof List) {
                            EnumMap<DexHookPlan.Role, String> result = bindFormat((String) format, (List<?>) array);
                            if (result.size() == DexHookPlan.Role.values().length
                                    && new HashSet<>(result.values()).size() == result.size()) return result;
                        }
                    }
                }
                pending = null;
            } else if (opcode == Opcode.MOVE_OBJECT || opcode == Opcode.MOVE_OBJECT_FROM16 || opcode == Opcode.MOVE_OBJECT_16) {
                TwoRegisterInstruction move = (TwoRegisterInstruction) instruction;
                values.put(move.getRegisterA(), values.get(move.getRegisterB()));
            } else if (opcode.setsRegister() && instruction instanceof OneRegisterInstruction) {
                values.put(((OneRegisterInstruction) instruction).getRegisterA(),
                        instruction instanceof NarrowLiteralInstruction
                                ? ((NarrowLiteralInstruction) instruction).getNarrowLiteral() : null);
            }
        }
        return null;
    }

    private static EnumMap<DexHookPlan.Role, String> bindFormat(String format, List<?> args) {
        EnumMap<DexHookPlan.Role, String> fields = new EnumMap<>(DexHookPlan.Role.class);
        java.util.regex.Matcher placeholders = java.util.regex.Pattern.compile("%(?:(\\d+)\\$)?s").matcher(format);
        int next = 0;
        int previous = 0;
        while (placeholders.find()) {
            int index = placeholders.group(1) == null ? next++ : Integer.parseInt(placeholders.group(1)) - 1;
            List<DexHookPlan.Role> role = labels(format.substring(previous, placeholders.start()));
            if (role.size() == 1 && index >= 0 && index < args.size() && args.get(index) instanceof FieldReference) {
                if (fields.put(role.get(0), ((FieldReference) args.get(index)).getName()) != null) return new EnumMap<>(DexHookPlan.Role.class);
            }
            previous = placeholders.end();
        }
        return fields;
    }

    static List<DexHookPlan.Role> labels(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        Map<DexHookPlan.Role, Integer> locations = new EnumMap<>(DexHookPlan.Role.class);
        String[] labels = {"deviceid:", "imem:", "sn:", "mac:", "model:"};
        DexHookPlan.Role[] roles = DexHookPlan.Role.values();
        for (int index = 0; index < labels.length; index++) {
            int location = lower.indexOf(labels[index]);
            if (index == 1 && location < 0) location = lower.indexOf("imei:");
            if (location >= 0) locations.put(roles[index], location);
        }
        List<DexHookPlan.Role> result = new ArrayList<>(locations.keySet());
        result.sort(Comparator.comparingInt(locations::get));
        return result;
    }

    private void discoverInfo(ClassDef type, EnumMap<DexHookPlan.Role, String> fields) {
        DexHookPlan.Info info = new DexHookPlan.Info(type.getType(), fields);
        boolean constructor = false;
        for (Method method : type.getMethods()) {
            List<String> params = parameters(method);
            if (type.getType().equals(method.getReturnType()) && params.contains(CONTEXT)) {
                info.factories.add(id(method));
            }
            if (!"<init>".equals(method.getName()) || !params.contains(CONTEXT)
                    || method.getImplementation() == null) continue;
            constructor = true;
            DexConstructorFlow.discover(method, info);
        }
        if (constructor && !info.factories.isEmpty()) plan.infos.add(info);
        else plan.rejected++;
    }

    private void discoverCredentials() {
        for (DexHookPlan.MethodId source : fidCalls) {
            ClassDef contract = classes.get(source.owner);
            if (contract == null || !hasSignContract(contract)) continue;
            for (ClassDef candidate : classes.values()) {
                if (!descends(candidate.getType(), source.owner)) continue;
                for (Method method : candidate.getMethods()) {
                    if (method.getName().equals(source.name) && STRING.equals(method.getReturnType())
                            && method.getParameterTypes().isEmpty()
                            && !AccessFlags.ABSTRACT.isSet(method.getAccessFlags())) {
                        plan.credentials.add(id(method));
                    }
                }
            }
        }
    }

    private boolean hasSignContract(ClassDef type) {
        for (Method method : type.getMethods()) {
            if ("[B".equals(method.getReturnType())
                    && parameters(method).equals(Collections.singletonList("[B"))) return true;
        }
        return false;
    }

    private void discoverTransports() {
        Set<String> candidates = new LinkedHashSet<>(httpWorkers);
        for (String worker : httpWorkers) {
            for (Field field : classes.get(worker).getInstanceFields()) {
                if (classes.containsKey(field.getType())) candidates.add(field.getType());
            }
        }
        for (String owner : candidates) {
            ClassDef type = classes.get(owner);
            Set<DexHookPlan.MethodId> selected = new LinkedHashSet<>();
            for (Method method : type.getMethods()) {
                if (!method.getName().startsWith("<")
                        && FindDeviceTransportLayout.sender(parameters(method))) selected.add(id(method));
            }
            if (selected.isEmpty()) continue;
            // Only wrappers which delegate to verified senders are accepted.
            boolean changed;
            do {
                changed = false;
                for (Method method : type.getMethods()) {
                    if (method.getName().startsWith("<") || selected.contains(id(method))
                            || !FindDeviceTransportLayout.recognizes(parameters(method))
                            || method.getImplementation() == null) continue;
                    for (Instruction instruction : method.getImplementation().getInstructions()) {
                        Reference ref = reference(instruction);
                        if (ref instanceof MethodReference && selected.contains(id((MethodReference) ref))) {
                            selected.add(id(method));
                            changed = true;
                            break;
                        }
                    }
                }
            } while (changed);
            plan.transports.addAll(selected);
        }
    }

    private boolean descends(String candidate, String ancestor) {
        Deque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(candidate);
        while (!queue.isEmpty()) {
            String current = queue.remove();
            if (current.equals(ancestor)) return true;
            if (!visited.add(current)) continue;
            ClassDef type = classes.get(current);
            if (type != null) {
                if (type.getSuperclass() != null) queue.add(type.getSuperclass());
                queue.addAll(type.getInterfaces());
            }
        }
        return false;
    }

    /** Track only strings and invoked String-returning methods. Never execute app code. */
    private static Object track(Instruction instruction, Reference ref,
            Map<Integer, Object> registers, Object pending) {
        Opcode opcode = instruction.getOpcode();
        if (opcode.name().startsWith("INVOKE_")) {
            return ref instanceof MethodReference && STRING.equals(((MethodReference) ref).getReturnType())
                    ? id((MethodReference) ref) : null;
        }
        if (opcode == Opcode.MOVE_RESULT_OBJECT) {
            int dest = ((OneRegisterInstruction) instruction).getRegisterA();
            registers.remove(dest);
            if (pending != null) registers.put(dest, pending);
            return null;
        }
        if (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) {
            registers.put(((OneRegisterInstruction) instruction).getRegisterA(), ((StringReference) ref).getString());
        } else if (opcode == Opcode.MOVE_OBJECT || opcode == Opcode.MOVE_OBJECT_FROM16 || opcode == Opcode.MOVE_OBJECT_16) {
            TwoRegisterInstruction move = (TwoRegisterInstruction) instruction;
            Object value = registers.get(move.getRegisterB());
            registers.remove(move.getRegisterA());
            if (value != null) registers.put(move.getRegisterA(), value);
        } else if (opcode.setsRegister() && instruction instanceof OneRegisterInstruction) {
            registers.remove(((OneRegisterInstruction) instruction).getRegisterA());
        }
        return null;
    }

    private static Reference reference(Instruction instruction) {
        return instruction instanceof ReferenceInstruction ? ((ReferenceInstruction) instruction).getReference() : null;
    }

    private static int[] arguments(Instruction instruction) {
        if (instruction instanceof FiveRegisterInstruction) {
            FiveRegisterInstruction call = (FiveRegisterInstruction) instruction;
            int[] all = {call.getRegisterC(), call.getRegisterD(), call.getRegisterE(), call.getRegisterF(), call.getRegisterG()};
            return Arrays.copyOf(all, call.getRegisterCount());
        }
        if (instruction instanceof RegisterRangeInstruction) {
            RegisterRangeInstruction call = (RegisterRangeInstruction) instruction;
            int[] result = new int[call.getRegisterCount()];
            for (int index = 0; index < result.length; index++) result[index] = call.getStartRegister() + index;
            return result;
        }
        return new int[0];
    }

    private static int mapCount(Method method) {
        int count = 0;
        for (CharSequence type : method.getParameterTypes()) if (isMap(type.toString())) count++;
        return count;
    }

    private static boolean isMap(String type) {
        return "Ljava/util/Map;".equals(type) || "Ljava/util/HashMap;".equals(type)
                || "Ljava/util/LinkedHashMap;".equals(type) || "Ljava/util/TreeMap;".equals(type);
    }

    static List<String> parameters(MethodReference method) {
        List<String> result = new ArrayList<>();
        for (CharSequence parameter : method.getParameterTypes()) result.add(parameter.toString());
        return result;
    }

    private static DexHookPlan.MethodId id(MethodReference method) {
        return new DexHookPlan.MethodId(method.getDefiningClass(), method.getName(),
                method.getParameterTypes(), method.getReturnType());
    }
}
