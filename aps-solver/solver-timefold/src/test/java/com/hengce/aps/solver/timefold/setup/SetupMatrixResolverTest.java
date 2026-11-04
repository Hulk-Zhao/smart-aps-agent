package com.hengce.aps.solver.timefold.setup;

import com.hengce.aps.solver.api.SetupMatrixEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 换型矩阵匹配优先级：资源指定 > 通配、键精确 > 通配、同优先级取较大值。
 */
class SetupMatrixResolverTest {

    private static final UUID RESOURCE_ID = UUID.randomUUID();

    @Test
    void resourceSpecificEntryWinsOverWildcard() {
        List<SetupMatrixEntry> matrix = List.of(
                new SetupMatrixEntry(null, "OPERATION_CODE", null, null, 600),
                new SetupMatrixEntry(RESOURCE_ID, "OPERATION_CODE", null, null, 1200));

        assertEquals(1200L, SetupMatrixResolver.resolve(
                matrix, RESOURCE_ID, "OP-20", "MAT-1", "OP-10", "MAT-1"));
    }

    @Test
    void exactKeysWinOverWildcard() {
        List<SetupMatrixEntry> matrix = List.of(
                new SetupMatrixEntry(null, "OPERATION_CODE", null, null, 600),
                new SetupMatrixEntry(null, "OPERATION_CODE", "OP-10", "OP-20", 1800));

        assertEquals(1800L, SetupMatrixResolver.resolve(
                matrix, RESOURCE_ID, "OP-20", "MAT-1", "OP-10", "MAT-1"));
    }

    @Test
    void samePriorityTakesLargerSetup() {
        List<SetupMatrixEntry> matrix = List.of(
                new SetupMatrixEntry(null, "OPERATION_CODE", "OP-10", "OP-20", 900),
                new SetupMatrixEntry(null, "OPERATION_CODE", "OP-10", "OP-20", 1500));

        assertEquals(1500L, SetupMatrixResolver.resolve(
                matrix, RESOURCE_ID, "OP-20", "MAT-1", "OP-10", "MAT-1"));
    }

    @Test
    void materialKeyTypeMatchesMaterialCode() {
        List<SetupMatrixEntry> matrix = List.of(
                new SetupMatrixEntry(null, "MATERIAL_CODE", "MAT-A", "MAT-B", 2400));

        assertEquals(2400L, SetupMatrixResolver.resolve(
                matrix, RESOURCE_ID, "OP-20", "MAT-B", "OP-10", "MAT-A"));
    }

    @Test
    void noMatchReturnsNull() {
        List<SetupMatrixEntry> matrix = List.of(
                new SetupMatrixEntry(null, "OPERATION_CODE", "OP-99", "OP-20", 900));

        assertNull(SetupMatrixResolver.resolve(
                matrix, RESOURCE_ID, "OP-20", "MAT-1", "OP-10", "MAT-1"));
    }

    @Test
    void unknownKeyTypeIsIgnored() {
        List<SetupMatrixEntry> matrix = List.of(
                new SetupMatrixEntry(null, "UNKNOWN", null, null, 900));

        assertNull(SetupMatrixResolver.resolve(
                matrix, RESOURCE_ID, "OP-20", "MAT-1", "OP-10", "MAT-1"));
    }

    @Test
    void negativeSetupIsRejected() {
        List<SetupMatrixEntry> matrix = List.of(
                new SetupMatrixEntry(null, "OPERATION_CODE", null, null, -1));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> SetupMatrixResolver.resolve(
                        matrix, RESOURCE_ID, "OP-20", "MAT-1", "OP-10", "MAT-1"));
    }
}
