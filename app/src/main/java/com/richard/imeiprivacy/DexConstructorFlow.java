package com.richard.imeiprivacy;

import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.OffsetInstruction;
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction;
import org.jf.dexlib2.iface.reference.FieldReference;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.iface.reference.Reference;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Branch-aware origins: the empty-ID branch must not erase the real-ID reader. */
final class DexConstructorFlow {
    private DexConstructorFlow() {}

    static void discover(Method method, DexHookPlan.Info info) {
        List<Instruction> code = new ArrayList<>();
        Map<Integer, Integer> atOffset = new HashMap<>();
        List<Integer> offsets = new ArrayList<>();
        int offset = 0;
        for (Instruction instruction : method.getImplementation().getInstructions()) {
            atOffset.put(offset, code.size());
            offsets.add(offset);
            code.add(instruction);
            offset += instruction.getCodeUnits();
        }
        if (code.isEmpty()) return;
        Map<Integer, State> entries = new HashMap<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        entries.put(0, new State());
        queue.add(0);
        while (!queue.isEmpty()) {
            int index = queue.remove();
            State state = new State(entries.get(index));
            Instruction instruction = code.get(index);
            Opcode opcode = instruction.getOpcode();
            Reference ref = instruction instanceof ReferenceInstruction
                    ? ((ReferenceInstruction) instruction).getReference() : null;
            if (opcode == Opcode.IPUT_OBJECT && ref instanceof FieldReference) {
                FieldReference field = (FieldReference) ref;
                if (info.owner.equals(field.getDefiningClass())) {
                    for (Map.Entry<DexHookPlan.Role, String> mapping : info.fields.entrySet()) {
                        if ((mapping.getKey() == DexHookPlan.Role.CORE_ID || mapping.getKey() == DexHookPlan.Role.MAC)
                                && mapping.getValue().equals(field.getName())) {
                            Set<DexHookPlan.MethodId> origins = state.registers.get(
                                    ((TwoRegisterInstruction) instruction).getRegisterA());
                            if (origins != null) info.readers.computeIfAbsent(mapping.getKey(), key -> new LinkedHashSet<>()).addAll(origins);
                        }
                    }
                }
            }
            state.execute(instruction, ref, info.owner);
            String name = opcode.name();
            if (instruction instanceof OffsetInstruction && (name.startsWith("IF_") || name.startsWith("GOTO"))) {
                Integer target = atOffset.get(offsets.get(index) + ((OffsetInstruction) instruction).getCodeOffset());
                if (target != null) merge(target, state, entries, queue);
            }
            if (!name.startsWith("GOTO") && !name.startsWith("RETURN") && opcode != Opcode.THROW
                    && index + 1 < code.size()) merge(index + 1, state, entries, queue);
        }
        Set<DexHookPlan.MethodId> core = info.readers.get(DexHookPlan.Role.CORE_ID);
        Set<DexHookPlan.MethodId> mac = info.readers.get(DexHookPlan.Role.MAC);
        if (core != null && mac != null) {
            Set<DexHookPlan.MethodId> ambiguous = new LinkedHashSet<>(core);
            ambiguous.retainAll(mac);
            core.removeAll(ambiguous);
            mac.removeAll(ambiguous);
        }
    }

    private static void merge(int index, State value, Map<Integer, State> entries, ArrayDeque<Integer> queue) {
        State existing = entries.get(index);
        if (existing == null) {
            entries.put(index, new State(value));
            queue.add(index);
        } else if (existing.merge(value)) queue.add(index);
    }

    private static final class State {
        final Map<Integer, Set<DexHookPlan.MethodId>> registers = new HashMap<>();
        Set<DexHookPlan.MethodId> pending = Collections.emptySet();

        State() {}
        State(State other) {
            other.registers.forEach((key, value) -> registers.put(key, new LinkedHashSet<>(value)));
            pending = new LinkedHashSet<>(other.pending);
        }

        boolean merge(State other) {
            boolean changed = false;
            for (Map.Entry<Integer, Set<DexHookPlan.MethodId>> entry : other.registers.entrySet()) {
                changed |= registers.computeIfAbsent(entry.getKey(), key -> new LinkedHashSet<>()).addAll(entry.getValue());
            }
            Set<DexHookPlan.MethodId> both = new LinkedHashSet<>(pending);
            changed |= both.addAll(other.pending);
            pending = both;
            return changed;
        }

        void execute(Instruction instruction, Reference ref, String owner) {
            Opcode opcode = instruction.getOpcode();
            if (opcode.name().startsWith("INVOKE_")) {
                pending = Collections.emptySet();
                if (ref instanceof MethodReference) {
                    MethodReference call = (MethodReference) ref;
                    List<String> parameters = DexSemanticDiscovery.parameters(call);
                    if (owner.equals(call.getDefiningClass()) && "Ljava/lang/String;".equals(call.getReturnType())
                            && parameters.contains("Landroid/content/Context;")) {
                        pending = Collections.singleton(new DexHookPlan.MethodId(owner, call.getName(), parameters, call.getReturnType()));
                    }
                }
            } else if (opcode == Opcode.MOVE_RESULT_OBJECT) {
                int dest = ((OneRegisterInstruction) instruction).getRegisterA();
                registers.put(dest, new LinkedHashSet<>(pending));
                pending = Collections.emptySet();
            } else if (opcode == Opcode.MOVE_OBJECT || opcode == Opcode.MOVE_OBJECT_FROM16 || opcode == Opcode.MOVE_OBJECT_16) {
                TwoRegisterInstruction move = (TwoRegisterInstruction) instruction;
                registers.put(move.getRegisterA(), new LinkedHashSet<>(
                        registers.getOrDefault(move.getRegisterB(), Collections.emptySet())));
            } else if (opcode.setsRegister() && instruction instanceof OneRegisterInstruction) {
                registers.remove(((OneRegisterInstruction) instruction).getRegisterA());
                pending = Collections.emptySet();
            } else {
                pending = Collections.emptySet();
            }
        }
    }
}
