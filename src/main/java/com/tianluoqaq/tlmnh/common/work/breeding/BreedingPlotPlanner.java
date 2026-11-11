package com.tianluoqaq.tlmnh.common.work.breeding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 3x3 CropsNH 育种田块几何拓扑规划器：
 * 拓扑 A（5 母本单架 + 4 正交双层十字架）覆盖同种提纯、变异池杂交及 2/3 亲本杂交；
 * 拓扑 B（4 正交亲本单架 + 1 中心双层十字架）专用于 4 亲本确定性杂交
 */
public final class BreedingPlotPlanner {

    public enum CellRole {
        PARENT_SINGLE,
        CROSS_DOUBLE,
        LEAVE_EMPTY
    }

    public static final class CellAssignment {

        public final int dx;
        public final int dz;
        public final CellRole role;
        public final String desiredParentCropId;

        public CellAssignment(int dx, int dz, CellRole role, String desiredParentCropId) {
            this.dx = dx;
            this.dz = dz;
            this.role = role;
            this.desiredParentCropId = desiredParentCropId == null ? "" : desiredParentCropId;
        }
    }

    private BreedingPlotPlanner() {}

    public static List<CellAssignment> planCells(boolean purifySameCrop, String targetCropId,
        List<String> crossParentIds) {
        if (purifySameCrop) {
            return buildTopologyAPurify(targetCropId);
        }
        List<String> parents = crossParentIds == null ? Collections.emptyList() : crossParentIds;
        if (parents.size() >= BreedingWorkTuning.FOUR) {
            return buildTopologyBFourParents(parents);
        }
        return buildTopologyAMultiParents(parents);
    }

    public static int requiredTotalSticks(List<CellAssignment> plan) {
        int sticks = BreedingWorkTuning.ZERO;
        for (CellAssignment cell : plan) {
            if (cell.role == CellRole.PARENT_SINGLE) {
                sticks += BreedingWorkTuning.ONE;
            } else if (cell.role == CellRole.CROSS_DOUBLE) {
                sticks += BreedingWorkTuning.TWO;
            }
        }
        return sticks;
    }

    private static List<CellAssignment> buildTopologyAPurify(String cropId) {
        int c = BreedingWorkTuning.OFFSET_CENTER;
        int p = BreedingWorkTuning.OFFSET_POS;
        int n = BreedingWorkTuning.OFFSET_NEG;
        List<CellAssignment> out = new ArrayList<>(BreedingWorkTuning.PLOT_CELL_COUNT);
        out.add(new CellAssignment(c, c, CellRole.PARENT_SINGLE, cropId));
        out.add(new CellAssignment(n, n, CellRole.PARENT_SINGLE, cropId));
        out.add(new CellAssignment(p, n, CellRole.PARENT_SINGLE, cropId));
        out.add(new CellAssignment(n, p, CellRole.PARENT_SINGLE, cropId));
        out.add(new CellAssignment(p, p, CellRole.PARENT_SINGLE, cropId));
        out.add(new CellAssignment(c, n, CellRole.CROSS_DOUBLE, ""));
        out.add(new CellAssignment(c, p, CellRole.CROSS_DOUBLE, ""));
        out.add(new CellAssignment(n, c, CellRole.CROSS_DOUBLE, ""));
        out.add(new CellAssignment(p, c, CellRole.CROSS_DOUBLE, ""));
        return Collections.unmodifiableList(out);
    }

    private static List<CellAssignment> buildTopologyAMultiParents(List<String> parents) {
        int c = BreedingWorkTuning.OFFSET_CENTER;
        int p = BreedingWorkTuning.OFFSET_POS;
        int n = BreedingWorkTuning.OFFSET_NEG;
        String p0 = parents.size() > BreedingWorkTuning.ZERO ? parents.get(BreedingWorkTuning.ZERO) : "";
        String p1 = parents.size() > BreedingWorkTuning.ONE ? parents.get(BreedingWorkTuning.ONE) : p0;
        String p2 = parents.size() > BreedingWorkTuning.TWO ? parents.get(BreedingWorkTuning.TWO) : "";

        List<CellAssignment> out = new ArrayList<>(BreedingWorkTuning.PLOT_CELL_COUNT);
        if (!p2.isEmpty()) {
            // 3 亲本：中心 P0，北两角 P1，南两角 P2 -> 西(-1,0) 与 东(1,0) 十字架同时相邻 P0/P1/P2
            out.add(new CellAssignment(c, c, CellRole.PARENT_SINGLE, p0));
            out.add(new CellAssignment(n, n, CellRole.PARENT_SINGLE, p1));
            out.add(new CellAssignment(p, n, CellRole.PARENT_SINGLE, p1));
            out.add(new CellAssignment(n, p, CellRole.PARENT_SINGLE, p2));
            out.add(new CellAssignment(p, p, CellRole.PARENT_SINGLE, p2));
        } else {
            // 2 亲本（或变异池前两亲本）：中心与主对角 P0，副对角 P1 -> 四个正交十字架均相邻 {P0, P1}
            out.add(new CellAssignment(c, c, CellRole.PARENT_SINGLE, p0));
            out.add(new CellAssignment(n, n, CellRole.PARENT_SINGLE, p0));
            out.add(new CellAssignment(p, p, CellRole.PARENT_SINGLE, p0));
            out.add(new CellAssignment(p, n, CellRole.PARENT_SINGLE, p1));
            out.add(new CellAssignment(n, p, CellRole.PARENT_SINGLE, p1));
        }
        out.add(new CellAssignment(c, n, CellRole.CROSS_DOUBLE, ""));
        out.add(new CellAssignment(c, p, CellRole.CROSS_DOUBLE, ""));
        out.add(new CellAssignment(n, c, CellRole.CROSS_DOUBLE, ""));
        out.add(new CellAssignment(p, c, CellRole.CROSS_DOUBLE, ""));
        return Collections.unmodifiableList(out);
    }

    private static List<CellAssignment> buildTopologyBFourParents(List<String> parents) {
        int c = BreedingWorkTuning.OFFSET_CENTER;
        int p = BreedingWorkTuning.OFFSET_POS;
        int n = BreedingWorkTuning.OFFSET_NEG;
        List<CellAssignment> out = new ArrayList<>(BreedingWorkTuning.PLOT_CELL_COUNT);
        out.add(new CellAssignment(c, n, CellRole.PARENT_SINGLE, parents.get(BreedingWorkTuning.ZERO)));
        out.add(new CellAssignment(c, p, CellRole.PARENT_SINGLE, parents.get(BreedingWorkTuning.ONE)));
        out.add(new CellAssignment(n, c, CellRole.PARENT_SINGLE, parents.get(BreedingWorkTuning.TWO)));
        out.add(new CellAssignment(p, c, CellRole.PARENT_SINGLE, parents.get(BreedingWorkTuning.THREE)));
        out.add(new CellAssignment(c, c, CellRole.CROSS_DOUBLE, ""));
        out.add(new CellAssignment(n, n, CellRole.LEAVE_EMPTY, ""));
        out.add(new CellAssignment(p, n, CellRole.LEAVE_EMPTY, ""));
        out.add(new CellAssignment(n, p, CellRole.LEAVE_EMPTY, ""));
        out.add(new CellAssignment(p, p, CellRole.LEAVE_EMPTY, ""));
        return Collections.unmodifiableList(out);
    }
}
