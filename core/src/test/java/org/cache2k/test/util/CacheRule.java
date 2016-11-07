package org.cache2k.test.util;

/*
 * #%L
 * cache2k core
 * %%
 * Copyright (C) 2000 - 2016 headissue GmbH, Munich
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.cache2k.configuration.CacheTypeCapture;
import org.cache2k.configuration.CacheType;
import org.cache2k.core.InternalCache;
import org.cache2k.core.InternalCacheInfo;
import org.cache2k.test.core.StaticUtil;
import org.cache2k.test.core.Statistics;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Jens Wilke
 */
public class CacheRule<K,V> implements TestRule {

  /**
   * Record classes that have shared caches, just to throw a better exception.
   */
  private static Map<String, String> sharedCache = new ConcurrentHashMap<String, String>();

  /** It is a class rule and we want to share the cache between the methods */
  private boolean shared;
  private Cache<K,V> cache;
  private Description description;
  private CacheType<K> keyType;
  private CacheType<V> valueType;
  private Statistics statistics;
  private List<Specialization> configurationSpecialization = new ArrayList<Specialization>();

  @SuppressWarnings("unchecked")
  protected CacheRule() {
    Type[] _types =
      ((ParameterizedType) this.getClass().getGenericSuperclass()).getActualTypeArguments();
    keyType =
      (CacheType<K>) CacheTypeCapture.of(_types[0]).getBeanRepresentation();
    valueType =
      (CacheType<V>) CacheTypeCapture.of(_types[1]).getBeanRepresentation();
  }

  private Cache2kBuilder<K, V> getInitialBuilder() {
    return Cache2kBuilder.forUnknownTypes()
      .keyType(keyType)
      .valueType(valueType)
      .entryCapacity(10000);
  }

  public CacheRule<K,V> config(Specialization<K,V> rb) {
    checkAlready();
    configurationSpecialization.add(rb);
    return this;
  }

  public CacheRule<K,V> enforecWiredCache() {
    checkAlready();
    configurationSpecialization.add(new Specialization() {
      @Override
      public void extend(final Cache2kBuilder b) {
        StaticUtil.enforceWiredCache(b);
      }
    });
    return this;
  }

  private void checkAlready() {
    if (cache != null) {
      throw new IllegalStateException("cache already build");
    }
  }

  /**
   * Create a cache or return an existing cache.
   */
  public Cache<K,V> cache() {
    if (cache == null) {
      provideCache();
    }
    return cache;
  }

  public Statistics statistics() {
    if (statistics == null) {
      statistics = new Statistics();
    }
    statistics.sample(cache());
    return statistics;
  }

  /**
   * Create a cache with additional special configuration.
   */
  public Cache<K,V> cache(Specialization<K,V> rb) {
    config(rb);
    provideCache();
    return cache;
  }

  public void run(Context<K,V> rb) {
    config(rb);
    provideCache();
    rb.cache = cache;
    rb.run();
  }

  /**
   * Return cache, expects it to be build or set already.
   */
  public Cache<K,V> getCache() {
    if (cache == null) {
      throw new NullPointerException("cache not yet built");
    }
    return cache;
  }

  /**
   * Set a pre built cache to be managed by this rule.
   */
  public void setCache(Cache<K,V> c) {
    checkAlready();
    cache = c;
  }

  public InternalCacheInfo info() {
    return cache.requestInterface(InternalCache.class).getLatestInfo();
  }

  @Override
  public Statement apply(final Statement st, final Description d) {
    if (d.isSuite()) {
      shared = true;
      description = d;
      return new Statement() {
        @Override
        public void evaluate() throws Throwable {
          try {
            st.evaluate();
          } finally {
            cleanupClass();
          }
        }
      };
    }
    if (d.isTest()) {
      description = d;
      return new Statement() {
        @Override
        public void evaluate() throws Throwable {
          try {
            st.evaluate();
          } finally {
            cleanupMethod();
          }
        }
      };
    }
    throw new UnsupportedOperationException("hey?");
  }

  void cleanupMethod() {
    if (shared) {
      try {
        cache.clear();
      } catch (Throwable ignore) { }
    } else {
      try {
        closeCache();
      } catch (Throwable ignore) { }
    }
  }

  public void closeCache() {
    cache.clear();
    cache.close();
    cache = null;
    statistics = null;
  }

  void cleanupClass() {
    if (cache != null) {
      try {
        closeCache();
      } catch (Throwable ignore) { }
    }
  }

  void provideCache() {
    if (shared) {
      if (cache == null) {
        cache = buildCache();
      }
      if (statistics != null) {
        statistics.reset();
      }
    } else {
      cache = buildCache();
    }
  }

  Cache<K, V> buildCache() {
    String _name = description.getTestClass().getName();
    Cache2kBuilder b = getInitialBuilder();
    for (Specialization sp : configurationSpecialization) {
      sp.extend(b);
    }
    if (shared) {
      b.name(description.getTestClass());
      sharedCache.put(_name, _name);
    } else {
      if (sharedCache.containsKey(_name)) {
        throw new IllegalArgumentException("Shared cache usage: Method rule must be identical instance.");
      }
      b.name(description.getTestClass(), description.getMethodName());
    }
    return b.build();
  }

  public interface Specialization<K,V> {
    void extend(Cache2kBuilder<K,V> b);
  }

  public static abstract class Context<K,V> implements Specialization<K,V>, Runnable {

    public Cache<K,V> cache;

    /** Used to run the tests with the context object */
    public void run() { }

  }

}
