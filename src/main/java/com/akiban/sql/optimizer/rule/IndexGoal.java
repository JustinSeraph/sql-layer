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

package com.akiban.sql.optimizer.rule;

import com.akiban.sql.optimizer.plan.*;

import com.akiban.sql.optimizer.plan.Sort.OrderByExpression;

import com.akiban.qp.expression.Comparison;

import com.akiban.ais.model.Column;
import com.akiban.ais.model.GroupIndex;
import com.akiban.ais.model.Index;
import com.akiban.ais.model.IndexColumn;
import com.akiban.ais.model.TableIndex;
import com.akiban.ais.model.UserTable;

import java.util.*;

/** A goal for indexing: conditions on joined tables and ordering / grouping. */
public class IndexGoal implements Comparator<IndexUsage>
{
    // Tables already bound outside.
    private Set<TableSource> boundTables;

    // All the conditions that might be indexable.
    private List<ConditionExpression> conditions;

    // If both grouping and ordering are present, they must be
    // compatible. Something satisfying the ordering would also handle
    // the grouping. All the order by columns must also be group by
    // columns, though not necessarily in the same order. There can't
    // be any additional order by columns, because even though it
    // would be properly grouped going into aggregation, it wouldn't
    // still be sorted by those coming out. It's hard to write such a
    // query in SQL, since the ORDER BY can't contain columns not in
    // the GROUP BY, and non-columns won't appear in the index.
    private List<ExpressionNode> grouping;
    private List<OrderByExpression> ordering;

    public IndexGoal(Set<TableSource> boundTables, 
                     List<ConditionExpression> conditions,
                     List<ExpressionNode> grouping,
                     List<OrderByExpression> ordering) {
        this.boundTables = boundTables;
        this.conditions = conditions;
        this.grouping = grouping;
        this.ordering = ordering;
    }

    /** Populate given index usage according to goal.
     * @return <code>false</code> if the index is useless.
     */
    public boolean usable(IndexUsage index) {
        List<IndexColumn> indexColumns = index.getIndex().getColumns();
        int ncols = indexColumns.size();
        int nequals = 0;
        while (nequals < ncols) {
            IndexColumn indexColumn = indexColumns.get(nequals);
            ExpressionNode indexExpression = getIndexExpression(index, indexColumn);
            if (indexExpression == null) break;
            ConditionExpression equalityCondition = null;
            ExpressionNode otherComparand = null;
            for (ConditionExpression condition : conditions) {
                if (condition instanceof ComparisonCondition) {
                    ComparisonCondition ccond = (ComparisonCondition)condition;
                    ExpressionNode comparand = null;
                    if (ccond.getOperation() == Comparison.EQ) {
                        if (indexExpression.equals(ccond.getLeft())) {
                            comparand = ccond.getRight();
                        }
                        else if (indexExpression.equals(ccond.getRight())) {
                            comparand = ccond.getLeft();
                        }
                    }
                    if ((comparand != null) && constantOrBound(comparand)) {
                        equalityCondition = condition;
                        otherComparand = comparand;
                        break;
                    }
                }
            }
            if (equalityCondition == null)
                break;
            index.addEqualityCondition(equalityCondition, otherComparand);
            nequals++;
        }
        if (nequals < ncols) {
            {
                IndexColumn indexColumn = indexColumns.get(nequals);
                ExpressionNode indexExpression = getIndexExpression(index, indexColumn);
                if (indexExpression != null) {
                    for (ConditionExpression condition : conditions) {
                        if (condition instanceof ComparisonCondition) {
                            ComparisonCondition ccond = (ComparisonCondition)condition;
                            ExpressionNode otherComparand = null;
                            if (indexExpression.equals(ccond.getLeft())) {
                                otherComparand = ccond.getRight();
                            }
                            else if (indexExpression.equals(ccond.getRight())) {
                                otherComparand = ccond.getLeft();
                            }
                            if (otherComparand != null) {
                                index.addInequalityCondition(condition,
                                                             ccond.getOperation(),
                                                             otherComparand);
                            }
                        }
                    }
                }
            }
            List<OrderByExpression> ordering = 
                new ArrayList<OrderByExpression>(ncols - nequals);
            for (int i = nequals; i < ncols; i++) {
                IndexColumn indexColumn = indexColumns.get(i);
                ExpressionNode indexExpression = getIndexExpression(index, indexColumn);
                if (indexExpression == null) break;
                ordering.add(new OrderByExpression(indexExpression, 
                                                   indexColumn.isAscending()));
            }
            index.setOrdering(ordering);
        }
        index.setOrderEffectiveness(determineOrderEffectiveness(index));
        return ((index.getOrderEffectiveness() != IndexUsage.OrderEffectiveness.NONE) ||
                (index.getConditions() != null));
    }

    // Determine how well this index does against the target.
    // Also, reverse the scan order if that helps. 
    // TODO: But see the comment on that field.
    protected IndexUsage.OrderEffectiveness
        determineOrderEffectiveness(IndexUsage index) {
        List<OrderByExpression> indexOrdering = index.getOrdering();
        List<ExpressionNode> equalityComparands = index.getEqualityComparands();
        IndexUsage.OrderEffectiveness result = IndexUsage.OrderEffectiveness.NONE;
        if (indexOrdering == null) return result;
        try_sorted:
        if (ordering != null) {
            Boolean reverse = null;
            int idx = 0;
            for (OrderByExpression targetColumn : ordering) {
                ExpressionNode targetExpression = targetColumn.getExpression();
                OrderByExpression indexColumn = null;
                if (idx < indexOrdering.size())
                    indexColumn = indexOrdering.get(idx);
                if ((indexColumn != null) && 
                    indexColumn.getExpression().equals(targetExpression)) {
                    if (reverse == null)
                        reverse = Boolean.valueOf(indexColumn.isAscending() != 
                                                  targetColumn.isAscending());
                    else if (reverse.booleanValue() != 
                             (indexColumn.isAscending() != targetColumn.isAscending()))
                        // Only good enough up to reversal of scan.
                        break try_sorted;
                    idx++;
                    continue;
                }
                if (equalityComparands != null) {
                    // Another possibility is that target ordering is
                    // in fact unchanged due to equality condition.
                    // TODO: Should this have been noticed earlier on
                    // so that it can be taken out of the sort?
                    if (equalityComparands.contains(targetExpression))
                        continue;
                }
                break try_sorted;
            }
            if (reverse != null)
                index.setReverseScan(reverse.booleanValue());
            result = IndexUsage.OrderEffectiveness.SORTED;
        }
        if (grouping != null) {
            boolean anyFound = false, allFound = true;
            for (ExpressionNode targetExpression : grouping) {
                int found = -1;
                for (int i = 0; i < indexOrdering.size(); i++) {
                    if (targetExpression.equals(indexOrdering.get(i).getExpression())) {
                        found = i;
                        break;
                    }
                }
                if (found < 0) {
                    allFound = false;
                    if ((equalityComparands == null) ||
                        !equalityComparands.contains(targetExpression))
                        continue;
                }
                else if (found >= grouping.size()) {
                    // Ordered by this column, but after some other
                    // stuff which will break up the group. Only
                    // partially grouped.
                    allFound = false;
                }
                anyFound = true;
            }
            if (anyFound) {
                if (!allFound)
                    return IndexUsage.OrderEffectiveness.PARTIAL_GROUPED;
                else if (result == IndexUsage.OrderEffectiveness.SORTED)
                    return result;
                else
                    return IndexUsage.OrderEffectiveness.GROUPED;
            }
        }
        return result;
    }

    protected class UnboundFinder implements ExpressionVisitor {
        boolean found = false;

        @Override
        public boolean visitEnter(ExpressionNode n) {
            return visit(n);
        }
        @Override
        public boolean visitLeave(ExpressionNode n) {
            return !found;
        }
        @Override
        public boolean visit(ExpressionNode n) {
            if (n instanceof ColumnExpression) {
                if (!boundTables.contains(((ColumnExpression)n).getTable())) {
                    found = true;
                    return false;
                }
            }
            else if (n instanceof SubqueryExpression) {
                found = true;
                return false;
            }
            return true;
        }
    }

    /** Does the given expression have references to tables that aren't bound? */
    protected boolean constantOrBound(ExpressionNode expression) {
        UnboundFinder f = new UnboundFinder();
        expression.accept(f);
        return !f.found;
    }

    /** Get an expression form of the given index column. */
    protected ExpressionNode getIndexExpression(IndexUsage index,
                                                IndexColumn indexColumn) {
        Column column = indexColumn.getColumn();
        UserTable indexTable = column.getUserTable();
        for (TableSource table = index.getLeafMostTable();
             null != table;
             table = table.getParentTable()) {
            if (table.getTable().getTable() == indexTable) {
                return new ColumnExpression(table, column);
            }
        }
        return null;
    }

    /** Find the best index on the given table. */
    public IndexUsage pickBestIndex(TableSource table) {
        IndexUsage bestIndex = null;
        for (TableIndex index : table.getTable().getTable().getIndexes()) {
            IndexUsage candidate = new IndexUsage(index, table, table);
            bestIndex = betterIndex(bestIndex, candidate);
        }
        if (table.getGroup() != null) {
            for (GroupIndex index : table.getGroup().getGroup().getIndexes()) {
                // The leaf must be used or else we'll get duplicates from a
                // scan (the indexed columns need not be root to leaf, making
                // ancestors discontiguous and duplicates hard to eliminate).
                if (index.leafMostTable() != table.getTable().getTable())
                    continue;
                // The root must be present, since the index does not
                // contain orphans.
                TableSource rootTable = table;
                while (rootTable != null) {
                    if (index.rootMostTable() == rootTable.getTable().getTable())
                        break;
                    rootTable = rootTable.getParentTable();
                }
                if (rootTable == null) continue;
                IndexUsage candidate = new IndexUsage(index, table, rootTable);
                bestIndex = betterIndex(bestIndex, candidate);
            
            }
        }
        return bestIndex;
    }

    protected IndexUsage betterIndex(IndexUsage bestIndex, IndexUsage candidate) {
        if (usable(candidate)) {
            if ((bestIndex == null) || (compare(candidate, bestIndex) > 0))
                return candidate;
        }
        return bestIndex;
    }

    /** Find the best index among the given tables. */
    public IndexUsage pickBestIndex(Collection<TableSource> tables) {
        IndexUsage bestIndex = null;
        for (TableSource table : tables) {
            IndexUsage tableIndex = pickBestIndex(table);
            if ((tableIndex != null) &&
                ((bestIndex == null) || (compare(tableIndex, bestIndex) > 0)))
                bestIndex = tableIndex;
        }
        return bestIndex;
    }

    // TODO: This is a pretty poor substitute for evidence-based comparison.
    public int compare(IndexUsage i1, IndexUsage i2) {
        if (i1.getOrderEffectiveness() != i2.getOrderEffectiveness())
            // These are ordered worst to best.
            return i1.getOrderEffectiveness().compareTo(i2.getOrderEffectiveness());
        if (i1.getEqualityComparands() != null) {
            if (i2.getEqualityComparands() == null)
                return +1;
            else if (i1.getEqualityComparands().size() !=
                     i2.getEqualityComparands().size())
                return (i1.getEqualityComparands().size() > 
                        i2.getEqualityComparands().size()) 
                    // More conditions tested better than fewer.
                    ? +1 : -1;
        }
        else if (i2.getEqualityComparands() != null)
            return -1;
        {
            int n1 = 0, n2 = 0;
            if (i1.getLowComparand() != null)
                n1++;
            if (i1.getHighComparand() != null)
                n1++;
            if (i2.getLowComparand() != null)
                n2++;
            if (i2.getHighComparand() != null)
                n2++;
            if (n1 != n2) 
                return (n1 > n2) ? +1 : -1;
        }
        if (i1.getIndex().getColumns().size() != i2.getIndex().getColumns().size())
            return (i1.getIndex().getColumns().size() < 
                    i2.getIndex().getColumns().size()) 
                // Fewer columns indexed better than more.
                ? +1 : -1;
        // Deeper better than shallower.
        return i1.getLeafMostTable().getTable().getTable().getTableId().compareTo(i2.getLeafMostTable().getTable().getTable().getTableId());
    }

}