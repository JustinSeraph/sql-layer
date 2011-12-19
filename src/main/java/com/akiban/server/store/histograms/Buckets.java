/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.server.store.histograms;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

final class Buckets<A extends Comparable<? super A>> {

    public static <A extends Comparable<A>> List<Bucket<A>> compile(int maxSize, Iterable<A> from) {
        BucketSource<A> source = new BucketSource<A>(from);
        Buckets<A> buckets = new Buckets<A>(maxSize);
        for (Bucket<A> bucket : source) {
            buckets.add(bucket);
        }
        return buckets.buckets();
    }

    public void add(Bucket<A> bucket) {
        // for all but the first entry, compare it to the previous entry
        if (sentinel.next != null && last.bucket.value().compareTo(bucket.value()) >= 1)
            throw new IllegalArgumentException("can't add " + bucket + " to " + buckets());
        BucketNode<A> node = nodeFor(bucket);
        node.prev = last;
        last.next = node;
        last = node;
        log("adding %s", bucket);
        log("%s", buckets());
        if (++size > maxSize) { // need to trim
            BucketNode<A> removeNode = nodeToRemove();
            // if the least popular node was the tail, we should fold it into its
            // prev. We can't do that, so instead, we'll fold its prev into it.
            if (removeNode.next == null)
                removeNode = removeNode.prev;   // to to
            Bucket<A> removeBucket = removeNode.bucket;

            Bucket<A> foldIntoBucket = removeNode.next.bucket;
            assert foldIntoBucket != null;
            foldIntoBucket.addLessThans(removeBucket.getEqualsCount() + removeBucket.getLessThanCount());
            foldIntoBucket.addLessThanDistincts(removeBucket.getLessThanDistinctsCount() + 1);
            // update the removeNode's prev and next to point to each other
            removeNode.prev.next = removeNode.next;
            if (removeNode.next != null)
                removeNode.next.prev = removeNode.prev;
            checkIntegrity();
        }
        log("%s", buckets());
        log("");
    }

    public List<Bucket<A>> buckets() {
        List<Bucket<A>> results = new ArrayList<Bucket<A>>(size);
        for(BucketNode<A> node = sentinel.next; node != null; node = node.next) {
            results.add(node.bucket);
        }
        return results;
    }

    private void checkIntegrity() {
        BucketNode<A> last = null;
        for(BucketNode<A> node = sentinel; node != null; node = node.next) {
            if (node.prev != last)
                System.out.printf("expected node.prev=%s but was %s%n", last, node.prev);
            last = node;
        }
    }

    public Buckets(int maxSize) {
        if (maxSize < 2)
            throw new IllegalArgumentException("max must be at least 2");
        this.maxSize = maxSize;
        this.sentinel = new BucketNode<A>();
        this.last = sentinel;
    }

    protected RemovalTieBreaker<BucketNode<A>> tieBreaker() {
        if (tieBreaker == null)
            tieBreaker = new FairRandomTieBreaker<BucketNode<A>>();
        return tieBreaker;
    }

    private BucketNode<A> nodeToRemove() {
        long lowestCount = Long.MAX_VALUE;
        BucketNode<A> result = null;
        RemovalTieBreaker<BucketNode<A>> tieBreaker = tieBreaker();
        tieBreaker.reset();
        for(BucketNode<A> node = sentinel.next; node != null; node = node.next) {
            long nodeEqs = node.bucket.getEqualsCount();
            if (nodeEqs == Long.MAX_VALUE)
                throw new IllegalStateException("node has too many counts: " + node);
            if (nodeEqs < lowestCount) {
                lowestCount = nodeEqs;
                tieBreaker.first();
            }
            else if (nodeEqs == lowestCount) {
                result = tieBreaker.choose(result, node);
            }
        }
        assert result != null;
        return result;
    }

    private BucketNode<A> nodeFor(Bucket<A> bucket) {
        if (bucket == null)
            throw new IllegalArgumentException("bucket may not be null");
        return new BucketNode<A>(bucket);
    }

    private void log(String format, Object... args) {
        System.out.print("\t-- ");
        System.out.printf(format, args);
        System.out.println();
    }

    private final int maxSize;
    private int size;
    private final BucketNode<A> sentinel;
    private BucketNode<A> last;
    private RemovalTieBreaker<BucketNode<A>> tieBreaker;

    private static class BucketNode<A> {

        public BucketNode() {
            this(null);
        }

        public BucketNode(Bucket<A> bucket) {
            this.bucket = bucket;
        }

        @Override
        public String toString() {
            return (prev==null) ? "SENTINAL" : String.valueOf(bucket);
        }

        final Bucket<A> bucket;
        BucketNode<A> next;
        BucketNode<A> prev;
    }

    private interface RemovalTieBreaker<A> {
        void reset();
        void first();
        A choose(A previous, A newGuy);
    }

    /**
     * <p>A tiebreaker which uses randomization, but such that all elements will eventually have an equal chance to
     * win.</p>
     *
     * <p>If we used an unbiased randomization to choose tiebreakers (ie, get a random boolean and pick either the
     * old or new value), we would have a bias toward later entries; the last entry would have a 50% chance of being
     * picked, the one before it 25%, etc.</p>
     *
     * <p>The FairRandomTieBreaker strategy gives each new element a 1/N chance of being picked, where
     * N is the number of elements involved in the tie, including this new element. For instance, if we
     * had seen 3 previous elements, and we assume that the last winner had a 1/3 chance of winning, then giving this
     * new element a 1/4th chance means the last winner has 3/4 chance of winning. That makes its overall chance of
     * winning all tiebreakers 1/4 * 3/4 = 3/12 = 1/4.</p>
     *
     * <p>We can prove this fairness inductively for N >= 2 (it would also work for N >= 1, but the
     * interfaces are a bit cleaner if we only start breaking ties at the second element).</p>
     *
     * <ul>
     *     <li><b>Base case (N = 2): </b> The previous element had been the only one, so it had a 1/1 chance in being
     *     picked. This new element has a 1/N chance of being picked, where N = 2, so it has a 1/2 chance of being
     *     picked. The previous element has a 1 - (1/2) chance of being picked, which is also 1/2.</li>
     *     <li><b>Inductive step:</b> Let P(E<sub>i, N</sub>) be the probability of any element i being chosen
     *     if there are N elements involved in the tiebreaker; 1 <= i < N. Show that if
     *     P(E<sub>i, N</sub>) == 1/N for any 1 <= i < N, then P(E<sub>i, N+1</sub>) == 1/(N+1) for any 1 <= i < N+1.
     *     <ol>
     *         <li>The probability of the newest element being chosen is <b>1/N+1</b>, so this trivially satisfies the
     *         requirement</li>
     *         <li>The probability of any other element being chosen is thus 1 - 1/(N+1), or (1-N+1)/(N+1), or
     *         N/(N+1). The probability of that element having been previously chosen was 1/N, so its overall
     *         probability of winning all contests (including this one) is 1/N * (N/N+1), or N/N(N+1), or
     *         <b>1/N+1</b>.</li>
     *     </ol>
     *     </li>
     * </ul>
     *
     * @param <A> type of element being chosen
     */
    private static class FairRandomTieBreaker<A> implements RemovalTieBreaker<A> {

        @Override
        public A choose(A previous, A newGuy) {
            assert count > 0 : count;
            ++count;
            return (random().nextInt(count) == 0)
                    ? newGuy
                    : previous;
        }

        @Override
        public void reset() {
            count = -1;
        }

        @Override
        public void first() {
            count = 1;
        }

        @Override
        public String toString() {
            return "tiebreaker n=" + count;
        }

        private Random random() {
            if (random == null)
                random = new Random(System.nanoTime());
            return random;
        }

        private int count;
        private Random random;
    }
}