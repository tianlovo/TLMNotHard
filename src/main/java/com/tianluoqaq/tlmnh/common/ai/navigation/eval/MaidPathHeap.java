package com.tianluoqaq.tlmnh.common.ai.navigation.eval;

import java.util.Arrays;

import com.tianluoqaq.tlmnh.common.ai.navigation.model.MaidPathNode;

/** 保留现代二叉堆严格比较和相等时右子节点的次序，避免通用队列改变路线 */
final class MaidPathHeap {

    private MaidPathNode[] entries = new MaidPathNode[NavigationEvalTuning.PATH_HEAP_DOMAIN_PARAM];
    private int size;

    boolean empty() {
        return size == 0;
    }

    void add(MaidPathNode node) {
        if (node.heapIndex >= 0) throw new IllegalStateException(NavigationEvalDiagnostics.MSG_NODE_ALREADY_QUEUED);
        if (size == entries.length)
            entries = Arrays.copyOf(entries, size * NavigationEvalTuning.AMPHIBIOUS_EVALUATOR_RAW_TYPE_THIRD_PARAM);
        entries[size] = node;
        node.heapIndex = size;
        up(size++);
    }

    MaidPathNode pop() {
        var node = entries[NavigationEvalTuning.COST_INITIAL_VALUE];
        entries[NavigationEvalTuning.COST_INITIAL_VALUE] = entries[--size];
        entries[size] = null;
        if (size > 0) down(NavigationEvalTuning.COST_INITIAL_VALUE);
        node.heapIndex = NavigationEvalTuning.UNSET_OR_NOT_FOUND;
        return node;
    }

    void cost(MaidPathNode node, float cost) {
        float old = node.f;
        node.f = cost;
        if (cost < old) up(node.heapIndex);
        else down(node.heapIndex);
    }

    private void up(int index) {
        var node = entries[index];
        while (index > 0) {
            int parent = (index - 1) >> NavigationEvalTuning.AMPHIBIOUS_EVALUATOR_BLOCK_TYPE_UNIT_STEP;
            var previous = entries[parent];
            if (!(node.f < previous.f)) break;
            entries[index] = previous;
            previous.heapIndex = index;
            index = parent;
        }
        entries[index] = node;
        node.heapIndex = index;
    }

    private void down(int index) {
        var node = entries[index];
        while (true) {
            int left = NavigationEvalTuning.AMPHIBIOUS_EVALUATOR_BLOCK_TYPE_UNIT_STEP
                + (index << NavigationEvalTuning.AMPHIBIOUS_EVALUATOR_BLOCK_TYPE_UNIT_STEP), right = left + 1;
            if (left >= size) break;
            float rightCost = right < size ? entries[right].f : Float.POSITIVE_INFINITY;
            int child = entries[left].f < rightCost ? left : right;
            float childCost = child < size ? entries[child].f : Float.POSITIVE_INFINITY;
            if (!(childCost < node.f)) break;
            entries[index] = entries[child];
            entries[index].heapIndex = index;
            index = child;
        }
        entries[index] = node;
        node.heapIndex = index;
    }
}
