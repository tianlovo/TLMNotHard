package com.tianluoqaq.tlmnh.common.ai.navigation;

import java.util.Arrays;

/** 保留现代二叉堆严格比较和相等时右子节点的次序，避免通用队列改变路线 */
final class MaidPathHeap {

    private MaidPathNode[] entries = new MaidPathNode[128];
    private int size;

    boolean empty() {
        return size == 0;
    }

    void add(MaidPathNode node) {
        if (node.heapIndex >= 0) throw new IllegalStateException("Node already queued");
        if (size == entries.length) entries = Arrays.copyOf(entries, size * 2);
        entries[size] = node;
        node.heapIndex = size;
        up(size++);
    }

    MaidPathNode pop() {
        var node = entries[0];
        entries[0] = entries[--size];
        entries[size] = null;
        if (size > 0) down(0);
        node.heapIndex = -1;
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
            int parent = (index - 1) >> 1;
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
            int left = 1 + (index << 1), right = left + 1;
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
