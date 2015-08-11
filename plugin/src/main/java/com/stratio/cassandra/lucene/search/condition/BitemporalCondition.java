/*
 * Copyright 2015, Stratio.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stratio.cassandra.lucene.search.condition;

import com.google.common.base.Objects;
import com.stratio.cassandra.lucene.IndexException;
import com.stratio.cassandra.lucene.schema.Schema;
import com.stratio.cassandra.lucene.schema.mapping.BitemporalMapper;
import com.stratio.cassandra.lucene.schema.mapping.Mapper;
import com.stratio.cassandra.lucene.util.Log;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.spatial.prefix.NumberRangePrefixTreeStrategy;
import org.apache.lucene.spatial.prefix.tree.DateRangePrefixTree;
import org.apache.lucene.spatial.query.SpatialArgs;
import org.apache.lucene.spatial.query.SpatialOperation;

import static org.apache.lucene.search.BooleanClause.Occur.MUST;
import static org.apache.lucene.search.BooleanClause.Occur.SHOULD;

/**
 * A {@link Condition} implementation that matches bi-temporal (four) fields within two range of values.
 *
 * @author Eduardo Alonso {@literal <eduardoalonso@stratio.com>}
 */
public class BitemporalCondition extends Condition {

    /** The default operation. */
    public static final String DEFAULT_OPERATION = "intersects";

    /** The default from value for vtFrom and ttFrom. */
    public static final Long DEFAULT_FROM = 0L;

    /** The default to value for vtTo and ttTo. */
    public static final Long DEFAULT_TO = Long.MAX_VALUE;

    /** The name of the field to be matched. */
    public final String field;

    /** The Valid Time Start. */
    public final Object vtFrom;

    /** The Valid Time End. */
    public final Object vtTo;

    /** The Transaction Time Start. */
    public final Object ttFrom;

    /** The Transaction Time End. */
    public final Object ttTo;

    /** The operation to be performed. */
    public final String operation;

    /** The spatial operation to be performed. */
    private final SpatialOperation spatialOperation;

    /**
     * Constructs a query selecting all fields that intersects with valid time and transaction time ranges including
     * limits.
     *
     * @param boost     The boost for this query clause. Documents matching this clause will (in addition to the normal
     *                  weightings) have their score multiplied by {@code boost}.
     * @param field     The name of the field to be matched.
     * @param vtFrom    The Valid Time Start.
     * @param vtTo      The Valid Time End.
     * @param ttFrom    The Transaction Time Start.
     * @param ttTo      The Transaction Time End.
     * @param operation The spatial operation to be performed.
     */
    public BitemporalCondition(Float boost,
                               String field,
                               Object vtFrom,
                               Object vtTo,
                               Object ttFrom,
                               Object ttTo,
                               String operation) {
        super(boost);
        this.field = field;
        this.vtFrom = vtFrom;
        this.vtTo = vtTo;
        this.ttFrom = ttFrom;
        this.ttTo = ttTo;
        this.operation = operation == null ? DEFAULT_OPERATION : operation;
        this.spatialOperation = parseSpatialOperation(this.operation);
    }

    private Query makeNormalQuery(BitemporalMapper mapper,
                                  NumberRangePrefixTreeStrategy strategy,
                                  DateRangePrefixTree tree,
                                  BitemporalMapper.BitemporalDateTime x_from,
                                  BitemporalMapper.BitemporalDateTime x_to) {
        SpatialArgs args = new SpatialArgs(this.spatialOperation, mapper.makeShape(tree, x_from, x_to));
        return strategy.makeQuery(args);
    }

    private static SpatialOperation parseSpatialOperation(String operation) {
        if (operation == null) {
            throw new IndexException("Operation is required");
        } else if (operation.equalsIgnoreCase("contains")) {
            return SpatialOperation.Contains;
        } else if (operation.equalsIgnoreCase("intersects")) {
            return SpatialOperation.Intersects;
        } else if (operation.equalsIgnoreCase("is_within")) {
            return SpatialOperation.IsWithin;
        } else {
            throw new IndexException("Operation is invalid: " + operation);
        }
    }

    @Override
    public Query query(Schema schema) {
        Log.debug("BITEMPCOND: calling query with schema: " + schema.toString());
        Mapper mapper = schema.getMapper(field);
        if (!(mapper instanceof BitemporalMapper)) {
            throw new IndexException("BiTemporal mapper required");
        }
        BitemporalMapper bitemporalMapper = (BitemporalMapper) mapper;
        BitemporalMapper.BitemporalDateTime vt_from = this.vtFrom == null ?
                                                      new BitemporalMapper.BitemporalDateTime(DEFAULT_FROM) :
                                                      bitemporalMapper.parseBiTemporalDate(this.vtFrom);
        BitemporalMapper.BitemporalDateTime vt_to = this.vtTo == null ?
                                                    new BitemporalMapper.BitemporalDateTime(DEFAULT_TO) :
                                                    bitemporalMapper.parseBiTemporalDate(this.vtTo);
        BitemporalMapper.BitemporalDateTime tt_from = this.ttFrom == null ?
                                                      new BitemporalMapper.BitemporalDateTime(DEFAULT_FROM) :
                                                      bitemporalMapper.parseBiTemporalDate(this.ttFrom);
        BitemporalMapper.BitemporalDateTime tt_to = this.ttTo == null ?
                                                    new BitemporalMapper.BitemporalDateTime(DEFAULT_TO) :
                                                    bitemporalMapper.parseBiTemporalDate(this.ttTo);
        BooleanQuery query = new BooleanQuery();
        NumberRangePrefixTreeStrategy[] validTimeStrategies = new NumberRangePrefixTreeStrategy[4];
        DateRangePrefixTree[] validTimeTrees = new DateRangePrefixTree[4];
        NumberRangePrefixTreeStrategy[] transactionTimeStrategies = new NumberRangePrefixTreeStrategy[4];
        DateRangePrefixTree[] transactionTimeTrees = new DateRangePrefixTree[4];
        for (int i = 0; i < 4; i++) {
            validTimeStrategies[i] = bitemporalMapper.getStrategy(i, true);
            transactionTimeStrategies[i] = bitemporalMapper.getStrategy(i, false);
            validTimeTrees[i] = bitemporalMapper.getTree(i, true);
            transactionTimeTrees[i] = bitemporalMapper.getTree(i, false);
        }
        if (!tt_from.isNow() && (tt_to.compareTo(vt_from) >= 0)) {
            //R1,R2,R3,R4
            Log.debug("BITEMPCOND: making query to R1,R2,R3,R4");
            Query vQueryT1 = makeNormalQuery(bitemporalMapper,
                                             validTimeStrategies[0],
                                             validTimeTrees[0],
                                             BitemporalMapper.BitemporalDateTime.MIN,
                                             vt_to);
            Query tQueryT1 = makeNormalQuery(bitemporalMapper,
                                             transactionTimeStrategies[0],
                                             transactionTimeTrees[0],
                                             BitemporalMapper.BitemporalDateTime.MIN,
                                             tt_to);
            BooleanQuery t1Query = new BooleanQuery();
            t1Query.add(vQueryT1, MUST);
            t1Query.add(tQueryT1, MUST);
            query.add(t1Query, SHOULD);
            Query vQueryT2 = makeNormalQuery(bitemporalMapper,
                                             validTimeStrategies[1],
                                             validTimeTrees[1],
                                             vt_from,
                                             vt_to);
            Query tQueryT2 = makeNormalQuery(bitemporalMapper,
                                             transactionTimeStrategies[1],
                                             transactionTimeTrees[1],
                                             BitemporalMapper.BitemporalDateTime.MIN,
                                             tt_to);
            BooleanQuery t2Query = new BooleanQuery();
            t2Query.add(vQueryT2, MUST);
            t2Query.add(tQueryT2, MUST);
            query.add(t2Query, SHOULD);
            Query vQueryT3 = makeNormalQuery(bitemporalMapper,
                                             validTimeStrategies[2],
                                             validTimeTrees[2],
                                             BitemporalMapper.BitemporalDateTime.MIN,
                                             vt_to);
            Query tQueryT3 = makeNormalQuery(bitemporalMapper,
                                             transactionTimeStrategies[2],
                                             transactionTimeTrees[2],
                                             BitemporalMapper.BitemporalDateTime.max(tt_from, vt_from),
                                             tt_to);
            BooleanQuery t3Query = new BooleanQuery();
            t3Query.add(vQueryT3, MUST);
            t3Query.add(tQueryT3, MUST);
            query.add(t3Query, SHOULD);
            Query vQueryT4 = makeNormalQuery(bitemporalMapper,
                                             validTimeStrategies[3],
                                             validTimeTrees[3],
                                             vt_from,
                                             vt_to);
            Query tQueryT4 = makeNormalQuery(bitemporalMapper,
                                             transactionTimeStrategies[3],
                                             transactionTimeTrees[3],
                                             tt_from,
                                             tt_to);
            BooleanQuery t4Query = new BooleanQuery();
            t4Query.add(vQueryT4, MUST);
            t4Query.add(tQueryT4, MUST);
            query.add(t4Query, SHOULD);
        } else if ((!tt_from.isNow()) && (tt_to.compareTo(vt_from) < 0)) {
            //R2,R4
            Log.debug("BITEMPCOND: making query to R2,R4");
            Query vQueryT2 = makeNormalQuery(bitemporalMapper,
                                             validTimeStrategies[1],
                                             validTimeTrees[1],
                                             vt_from,
                                             vt_to);
            Query tQueryT2 = makeNormalQuery(bitemporalMapper,
                                             transactionTimeStrategies[1],
                                             transactionTimeTrees[1],
                                             BitemporalMapper.BitemporalDateTime.MIN,
                                             tt_to);
            BooleanQuery t2Query = new BooleanQuery();
            t2Query.add(vQueryT2, MUST);
            t2Query.add(tQueryT2, MUST);
            query.add(t2Query, SHOULD);
            Query vQueryT4 = makeNormalQuery(bitemporalMapper,
                                             validTimeStrategies[3],
                                             validTimeTrees[3],
                                             vt_from,
                                             vt_to);
            Query tQueryT4 = makeNormalQuery(bitemporalMapper,
                                             transactionTimeStrategies[3],
                                             transactionTimeTrees[3],
                                             tt_from,
                                             tt_to);
            BooleanQuery t4Query = new BooleanQuery();
            t4Query.add(vQueryT4, MUST);
            t4Query.add(tQueryT4, MUST);
            query.add(t4Query, SHOULD);
        } else if (tt_from.isNow()) {
            if (((!vt_from.isMin()) || (!vt_to.isMax())) && (tt_to.compareTo(vt_from) >= 0)) {
                //R1,R2
                Log.debug("BITEMPCOND: making query to R1,R2");
                Query vQueryT1 = makeNormalQuery(bitemporalMapper,
                                                 validTimeStrategies[0],
                                                 validTimeTrees[0],
                                                 BitemporalMapper.BitemporalDateTime.MIN,
                                                 vt_to);
                Query tQueryT1 = makeNormalQuery(bitemporalMapper,
                                                 transactionTimeStrategies[0],
                                                 transactionTimeTrees[0],
                                                 BitemporalMapper.BitemporalDateTime.MIN,
                                                 tt_to);
                BooleanQuery t1Query = new BooleanQuery();
                t1Query.add(vQueryT1, MUST);
                t1Query.add(tQueryT1, MUST);
                query.add(t1Query, SHOULD);
                Query vQueryT2 = makeNormalQuery(bitemporalMapper,
                                                 validTimeStrategies[1],
                                                 validTimeTrees[1],
                                                 vt_from,
                                                 vt_to);
                Query tQueryT2 = makeNormalQuery(bitemporalMapper,
                                                 transactionTimeStrategies[1],
                                                 transactionTimeTrees[1],
                                                 BitemporalMapper.BitemporalDateTime.MIN,
                                                 tt_to);
                BooleanQuery t2Query = new BooleanQuery();
                t2Query.add(vQueryT2, MUST);
                t2Query.add(tQueryT2, MUST);
                query.add(t2Query, SHOULD);
            } else if (((!vt_from.isMin()) || (!vt_to.isMax())) && (tt_to.compareTo(vt_from) < 0)) {
                //R2

                Query vQueryT2 = makeNormalQuery(bitemporalMapper,
                                                 validTimeStrategies[1],
                                                 validTimeTrees[1],
                                                 vt_from,
                                                 vt_to);
                Query tQueryT2 = makeNormalQuery(bitemporalMapper,
                                                 transactionTimeStrategies[1],
                                                 transactionTimeTrees[1],
                                                 BitemporalMapper.BitemporalDateTime.MIN,
                                                 tt_to);
                BooleanQuery t2Query = new BooleanQuery();
                t2Query.add(vQueryT2, MUST);
                t2Query.add(tQueryT2, MUST);
                query.add(t2Query, SHOULD);
            } else if ((vt_from.isMin()) && (vt_to.isMax())) { // [vtFrom, vtTo]==[tmin,tmax]])
                //R1,R2
                Log.debug("BITEMPCOND: making query to R1,R2--last");
                Query vQueryT1 = makeNormalQuery(bitemporalMapper,
                                                 validTimeStrategies[0],
                                                 validTimeTrees[0],
                                                 BitemporalMapper.BitemporalDateTime.MIN,
                                                 BitemporalMapper.BitemporalDateTime.MAX);
                Query tQueryT1 = makeNormalQuery(bitemporalMapper,
                                                 transactionTimeStrategies[0],
                                                 transactionTimeTrees[0],
                                                 BitemporalMapper.BitemporalDateTime.MIN,
                                                 BitemporalMapper.BitemporalDateTime.MAX);
                BooleanQuery t1Query = new BooleanQuery();
                t1Query.add(vQueryT1, MUST);
                t1Query.add(tQueryT1, MUST);
                query.add(t1Query, SHOULD);
                Query vQueryT2 = makeNormalQuery(bitemporalMapper,
                                                 validTimeStrategies[1],
                                                 validTimeTrees[1],
                                                 BitemporalMapper.BitemporalDateTime.MIN,
                                                 BitemporalMapper.BitemporalDateTime.MAX);
                Query tQueryT2 = makeNormalQuery(bitemporalMapper,
                                                 transactionTimeStrategies[1],
                                                 transactionTimeTrees[1],
                                                 BitemporalMapper.BitemporalDateTime.MIN,
                                                 BitemporalMapper.BitemporalDateTime.MAX);
                BooleanQuery t2Query = new BooleanQuery();
                t2Query.add(vQueryT2, MUST);
                t2Query.add(tQueryT2, MUST);
                query.add(t2Query, SHOULD);
            }
        }
        query.setBoost(boost);
        return query;
    }

    /**
     * {@inheritDoc}
     */
    /*@Override
    public Query query(Schema schema) {
        Mapper mapper = schema.getMapper(field);
        if (!(mapper instanceof BitemporalMapper)) {
            throw new IndexException("Bitemporal mapper required");
        }
        BitemporalMapper bitemporalMapper = (BitemporalMapper) mapper;

        BitemporalMapper.BitemporalDateTime vt_from = this.vtFrom == null ?
                                                      new BitemporalMapper.BitemporalDateTime(DEFAULT_FROM) :
                                                      bitemporalMapper.parseBiTemporalDate(this.vtFrom);
        BitemporalMapper.BitemporalDateTime vt_to = this.vtTo == null ?
                                                    new BitemporalMapper.BitemporalDateTime(DEFAULT_TO) :
                                                    bitemporalMapper.parseBiTemporalDate(this.vtTo);
        BitemporalMapper.BitemporalDateTime tt_from = this.ttFrom == null ?
                                                      new BitemporalMapper.BitemporalDateTime(DEFAULT_FROM) :
                                                      bitemporalMapper.parseBiTemporalDate(this.ttFrom);
        BitemporalMapper.BitemporalDateTime tt_to = this.ttTo == null ?
                                                    new BitemporalMapper.BitemporalDateTime(DEFAULT_TO) :
                                                    bitemporalMapper.parseBiTemporalDate(this.ttTo);

        BooleanQuery query = new BooleanQuery();
        NumberRangePrefixTreeStrategy[] validTimeStrategies = new NumberRangePrefixTreeStrategy[4];
        DateRangePrefixTree[] validTimeTrees = new DateRangePrefixTree[4];
        NumberRangePrefixTreeStrategy[] transactionTimeStrategies = new NumberRangePrefixTreeStrategy[4];
        DateRangePrefixTree[] transactionTimeTrees = new DateRangePrefixTree[4];
        for (int i = 0; i < 4; i++) {
            validTimeStrategies[i] = bitemporalMapper.getStrategy(i, true);
            transactionTimeStrategies[i] = bitemporalMapper.getStrategy(i, false);
            validTimeTrees[i] = bitemporalMapper.getTree(i, true);
            transactionTimeTrees[i] = bitemporalMapper.getTree(i, false);
        }
        if (vt_from.compareTo(tt_to) <= 0) { //vt_from<=tt_to
            Query vQueryT1 = makeNormalQuery(bitemporalMapper,
                                             validTimeStrategies[0],
                                             validTimeTrees[0],
                                             BitemporalMapper.BitemporalDateTime.MIN,
                                             vt_to);
            Query tQueryT1 = makeNormalQuery(bitemporalMapper,
                                             transactionTimeStrategies[0],
                                             transactionTimeTrees[0],
                                             BitemporalMapper.BitemporalDateTime.MIN,
                                             tt_to);
            BooleanQuery t1Query = new BooleanQuery();
            t1Query.add(vQueryT1, MUST);
            t1Query.add(tQueryT1, MUST);
            query.add(t1Query, SHOULD);

            Query vQueryT2 = makeNormalQuery(bitemporalMapper,
                                             validTimeStrategies[1],
                                             validTimeTrees[1],
                                             BitemporalMapper.BitemporalDateTime.MIN,
                                             vt_to);
            Query tQueryT2 = makeNormalQuery(bitemporalMapper,
                                             transactionTimeStrategies[1],
                                             transactionTimeTrees[1],
                                             BitemporalMapper.BitemporalDateTime.max(vt_from, tt_from),
                                             tt_to);
            BooleanQuery t2Query = new BooleanQuery();
            t2Query.add(vQueryT2, MUST);
            t2Query.add(tQueryT2, MUST);
            query.add(t2Query, SHOULD);
        }

        Query vQueryT3 = makeNormalQuery(bitemporalMapper, validTimeStrategies[2], validTimeTrees[2], vt_from, vt_to);
        Query tQueryT3 = makeNormalQuery(bitemporalMapper,
                                         transactionTimeStrategies[2],
                                         transactionTimeTrees[2],
                                         BitemporalMapper.BitemporalDateTime.MIN,
                                         tt_to);
        BooleanQuery t3Query = new BooleanQuery();
        t3Query.add(vQueryT3, MUST);
        t3Query.add(tQueryT3, MUST);
        query.add(t3Query, SHOULD);

        Query vQueryT4 = makeNormalQuery(bitemporalMapper, validTimeStrategies[3], validTimeTrees[3], vt_from, vt_to);
        Query tQueryT4 = makeNormalQuery(bitemporalMapper,
                                         transactionTimeStrategies[3],
                                         transactionTimeTrees[3],
                                         tt_from,
                                         tt_to);
        BooleanQuery t4Query = new BooleanQuery();
        t4Query.add(vQueryT4, MUST);
        t4Query.add(tQueryT4, MUST);
        query.add(t4Query, SHOULD);

        query.setBoost(boost);
        return query;
    }*/

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                      .add("boost", boost)
                      .add("field", field)
                      .add("vtFrom", vtFrom)
                      .add("vtTo", vtTo)
                      .add("ttFrom", ttFrom)
                      .add("ttTo", ttTo)
                      .toString();
    }
}
