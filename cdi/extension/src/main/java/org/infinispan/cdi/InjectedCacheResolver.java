package org.infinispan.cdi;

import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.jcache.JCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;

import javax.cache.Cache;
import javax.cache.Caching;
import javax.cache.annotation.CacheInvocationContext;
import javax.cache.annotation.CacheResolver;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Alternative;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Default;
import javax.enterprise.inject.Instance;
import javax.enterprise.util.AnnotationLiteral;
import javax.inject.Inject;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.infinispan.cdi.util.Contracts.assertNotNull;

/**
 * Injected cache resolver for situations where caches and/or cache managers
 * are injected into the CDI beans. In these situations, bridging is required
 * in order to bridge between the Infinispan based caches and the JCache
 * cache instances which is what it's expected by the specification cache
 * resolver.
 *
 * @author Galder Zamarreño
 * @since 5.3
 */
@ApplicationScoped
@Alternative
public class InjectedCacheResolver implements CacheResolver {

   private Instance<EmbeddedCacheManager> cacheManagers;
   private EmbeddedCacheManager defaultCacheManager;

   private Map<EmbeddedCacheManager, JCacheManager> jcacheManagers =
         new HashMap<EmbeddedCacheManager, JCacheManager>();
   private JCacheManager defaultJCacheManager;

   @Inject
   public InjectedCacheResolver(@Any Instance<EmbeddedCacheManager> cacheManagers) {
      this.cacheManagers = cacheManagers;
      for (EmbeddedCacheManager cacheManager : cacheManagers)
         jcacheManagers.put(cacheManager, toJCacheManager(cacheManager));

      this.defaultCacheManager = cacheManagers.select(new AnnotationLiteral<Default>() {}).get();
      this.defaultJCacheManager = jcacheManagers.get(defaultCacheManager);
   }

   private JCacheManager toJCacheManager(EmbeddedCacheManager cacheManager) {
      GlobalConfiguration globalCfg = cacheManager.getCacheManagerConfiguration();
      String name = globalCfg.globalJmxStatistics().cacheManagerName();
      return new JCacheManager(URI.create(name), cacheManager, Caching.getCachingProvider());
   }

   // for proxy.
   InjectedCacheResolver() {
   }

   @Override
   public <K, V> Cache<K, V> resolveCache(CacheInvocationContext<? extends Annotation> cacheInvocationContext) {
      assertNotNull(cacheInvocationContext, "cacheInvocationContext parameter must not be null");

      final String cacheName = cacheInvocationContext.getCacheName();

      // If the cache name is empty the default cache of the default cache manager is returned.
      if (cacheName.trim().isEmpty()) {
         return getCacheFromDefaultCacheManager(cacheName);
      }

      // Iterate on all cache managers because the cache used by the
      // interceptor could use a specific cache manager.
      for (EmbeddedCacheManager cm : cacheManagers) {
         Set<String> cacheNames = cm.getCacheNames();
         for (String name : cacheNames) {
            if (name.equals(cacheName)) {
               JCacheManager jcacheManager = jcacheManagers.get(cm);
               Cache<K, V> cache = jcacheManager.getCache(cacheName);
               if (cache != null)
                  return cache;

               return jcacheManager.configureCache(
                     cacheName, cm.<K, V>getCache(cacheName).getAdvancedCache());
            }
         }
      }

      // If the cache has not been defined in the default cache manager
      // or in a specific one a new cache is created in the default
      // cache manager with the default configuration.
      return getCacheFromDefaultCacheManager(cacheName);
   }

   private <K, V> Cache<K, V> getCacheFromDefaultCacheManager(String cacheName) {
      Cache<K, V> cache = defaultJCacheManager.getCache(cacheName);
      if (cache != null)
         return cache;

      return defaultJCacheManager.configureCache(cacheName,
            defaultCacheManager.<K, V>getCache().getAdvancedCache());
   }

}
