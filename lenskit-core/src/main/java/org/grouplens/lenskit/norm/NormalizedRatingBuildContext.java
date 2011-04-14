/*
 * LensKit, a reference implementation of recommender algorithms.
 * Copyright 2010-2011 Regents of the University of Minnesota
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package org.grouplens.lenskit.norm;

import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongIterator;

import java.util.AbstractCollection;
import java.util.Iterator;

import org.grouplens.lenskit.AbstractRecommenderComponentBuilder;
import org.grouplens.lenskit.RecommenderComponentBuilder;
import org.grouplens.lenskit.data.Index;
import org.grouplens.lenskit.data.IndexedRating;
import org.grouplens.lenskit.data.Ratings;
import org.grouplens.lenskit.data.SimpleIndexedRating;
import org.grouplens.lenskit.data.context.AbstractRatingBuildContext;
import org.grouplens.lenskit.data.context.PackedRatingBuildContext;
import org.grouplens.lenskit.data.context.RatingBuildContext;
import org.grouplens.lenskit.data.dao.RatingDataAccessObject;
import org.grouplens.lenskit.data.vector.MutableSparseVector;
import org.grouplens.lenskit.data.vector.SparseVector;
import org.grouplens.lenskit.util.FastCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rating build context wrapper that provides normalized ratings. They are built
 * with a {@link NormalizedRatingBuildContext#Builder}.
 * 
 * <p>This class wraps the rating build context to provide pre-normalized ratings.
 * It should share the same scope as the rating build context, so if you re-scope
 * {@link PackedRatingBuildContext} (or some other rating build context implementation)
 * in your Guice configuration, you must re-scope this class as well.
 * 
 * <p>This class
 * also breaks the rule that rating build contexts shouldn't be retained, but
 * since its scope is intended to be identical to the rating build context itself,
 * that is OK.
 * 
 * <p>This class also computes the normed data lazily, so the computation cost
 * isn't incurred unless necessary even when injected as a singleton in the Guice
 * PRODUCTION scope.
 * 
 * <p><strong>Warning:</strong> Do not bind this class as the implementation of
 * {@link RatingBuildContext} in any Guice configuration, as that will implement
 * circular loops and general brokenness. Classes which want a normalized rating
 * build context should depend on it directly.
 * 
 * @author Michael Ekstrand <ekstrand@cs.umn.edu>
 *
 */
public class NormalizedRatingBuildContext extends AbstractRatingBuildContext {
    /**
     * A RecommenderComponentBuilder used to create
     * NormalizedRatingBuildContexts with a specific
     * {@link UserRatingVectorNormalizer}.
     * 
     * @author Michael Ludwig <mludwig@cs.umn.edu>
     */
    public static class Builder extends AbstractRecommenderComponentBuilder<NormalizedRatingBuildContext> {
        private RecommenderComponentBuilder<? extends UserRatingVectorNormalizer> normalizer;
        
        public Builder() {
            normalizer = new IdentityUserRatingVectorNormalizer.Builder();
        }
        
        public RecommenderComponentBuilder<? extends UserRatingVectorNormalizer> getNormalizer() {
            return normalizer;
        }
        
        public void setNormalizer(RecommenderComponentBuilder<? extends UserRatingVectorNormalizer> normalizer) {
            this.normalizer = normalizer;
        }
        
        @Override
        protected NormalizedRatingBuildContext buildNew(RatingBuildContext context) {
            UserRatingVectorNormalizer n = normalizer.build(context);
            return new NormalizedRatingBuildContext(context, n);
        }
    }
    
    private static final Logger logger = LoggerFactory.getLogger(NormalizedRatingBuildContext.class);
    private final RatingBuildContext buildContext;
    private final UserRatingVectorNormalizer normalizer;
    private SparseVector[] normedData;
    
    private NormalizedRatingBuildContext(RatingBuildContext context, UserRatingVectorNormalizer norm) {
        buildContext = context;
        normalizer = norm;
    }
    
    private synchronized void requireNormedData() {
        if (normedData == null) {
            logger.debug("Computing normalized build context");
            LongCollection users = buildContext.getUserIds();
            normedData = new SparseVector[users.size()];
            LongIterator uit = users.iterator();
            Index uidx = buildContext.userIndex();
            int ndone = 0; // for debugging
            while (uit.hasNext()) {
                final long uid = uit.nextLong();
                final int i = uidx.getIndex(uid);
                assert normedData[i] == null;
                MutableSparseVector rv = Ratings.userRatingVector(buildContext.getUserRatings(uid));
                normalizer.normalize(uid, rv);
                normedData[i] = rv;
                ndone++;
            }
            assert ndone == normedData.length;
        }
    }
    
    public UserRatingVectorNormalizer getNormalizer() {
    	return normalizer;
    }
    
    @Override
    public RatingDataAccessObject getDAO() {
        return buildContext.getDAO();
    }

    @Override
    public LongCollection getUserIds() {
        return buildContext.getUserIds();
    }

    @Override
    public LongCollection getItemIds() {
        return buildContext.getItemIds();
    }

    @Override
    public Index userIndex() {
        return buildContext.userIndex();
    }

    @Override
    public Index itemIndex() {
        return buildContext.itemIndex();
    }

    @Override
    public FastCollection<IndexedRating> getRatings() {
        requireNormedData();
        return new NormalizingCollection(normedData, buildContext.getRatings());
    }

    @Override
    public FastCollection<IndexedRating> getUserRatings(long userId) {
        requireNormedData();
        return new NormalizingCollection(normedData, buildContext.getUserRatings(userId));
    }

    /**
     * "Close" the build context by clearing its internal data. Does not close
     * the root build context.
     */
    @Override
    public synchronized void close() {
        normedData = null;
    }
    
    private static class NormalizingCollection extends AbstractCollection<IndexedRating> 
            implements FastCollection<IndexedRating> {
        private FastCollection<IndexedRating> base;
        private SparseVector[] normedData;
        public NormalizingCollection(SparseVector[] nd, FastCollection<IndexedRating> base) {
            this.base = base;
            normedData = nd;
        }
        @Override
        public Iterator<IndexedRating> fastIterator() {
            return new Iterator<IndexedRating>() {
                private Iterator<IndexedRating> biter = base.fastIterator();
                NormedRating rating = new NormedRating();
                @Override public void remove() {
                    throw new UnsupportedOperationException();
                }
                @Override public boolean hasNext() {
                    return biter.hasNext();
                }
                @Override public IndexedRating next() {
                    rating.brating = biter.next();
                    return rating;
                }
            };
        }
        @Override
        public Iterable<IndexedRating> fast() {
            return new Iterable<IndexedRating>() {
                @Override public Iterator<IndexedRating> iterator() {
                    return fastIterator();
                }
            };
        }
        @Override
        public Iterator<IndexedRating> iterator() {
            return new Iterator<IndexedRating>() {
                Iterator<IndexedRating> biter = base.fastIterator();
                @Override public void remove() {
                    throw new UnsupportedOperationException();
                }
                @Override public boolean hasNext() {
                    return biter.hasNext();
                }
                @Override public IndexedRating next() {
                    IndexedRating r = biter.next();
                    long iid = r.getItemId();
                    int uidx = r.getUserIndex();
                    return new SimpleIndexedRating(r.getUserId(), iid, 
                            normedData[uidx].get(iid), 
                            r.getTimestamp(), uidx, r.getItemIndex());
                }
            };
        }
        @Override
        public int size() {
            return base.size();
        }
        
        private class NormedRating implements IndexedRating {
            IndexedRating brating = null;

            public long getUserId() {
                return brating.getUserId();
            }

            public int getUserIndex() {
                return brating.getUserIndex();
            }

            public long getItemId() {
                return brating.getItemId();
            }

            public double getRating() {
                return normedData[getUserIndex()].get(getItemId());
            }

            public int getItemIndex() {
                return brating.getItemIndex();
            }

            public long getTimestamp() {
                return brating.getTimestamp();
            }
            
        }
    }

}