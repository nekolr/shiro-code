/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.shiro.web.config;

import org.apache.shiro.config.Ini;
import org.apache.shiro.config.IniFactorySupport;
import org.apache.shiro.config.IniSecurityManagerFactory;
import org.apache.shiro.config.ReflectionBuilder;
import org.apache.shiro.util.CollectionUtils;
import org.apache.shiro.util.Factory;
import org.apache.shiro.web.filter.mgt.FilterChainManager;
import org.apache.shiro.web.filter.mgt.FilterChainResolver;
import org.apache.shiro.web.filter.mgt.PathMatchingFilterChainResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Filter;
import javax.servlet.FilterConfig;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@link Factory} that creates {@link FilterChainResolver} instances based on {@link Ini} configuration.
 *
 * @since 1.0
 */
public class IniFilterChainResolverFactory extends IniFactorySupport<FilterChainResolver> {

    public static final String FILTERS = "filters";
    public static final String URLS = "urls";

    private static transient final Logger log = LoggerFactory.getLogger(IniFilterChainResolverFactory.class);

    private FilterConfig filterConfig;

    public IniFilterChainResolverFactory() {
        super();
    }

    public IniFilterChainResolverFactory(Ini ini) {
        super(ini);
    }

    public IniFilterChainResolverFactory(Ini ini, Map<String, ?> defaultBeans) {
        this(ini);
        this.setDefaults(defaultBeans);
    }

    public FilterConfig getFilterConfig() {
        return filterConfig;
    }

    public void setFilterConfig(FilterConfig filterConfig) {
        this.filterConfig = filterConfig;
    }

    protected FilterChainResolver createInstance(Ini ini) {
        // 创建默认的实例
        FilterChainResolver filterChainResolver = createDefaultInstance();
        if (filterChainResolver instanceof PathMatchingFilterChainResolver) {
            PathMatchingFilterChainResolver resolver = (PathMatchingFilterChainResolver) filterChainResolver;
            // 获取 FilterChainManager
            FilterChainManager manager = resolver.getFilterChainManager();
            // 构建过滤器链
            buildChains(manager, ini);
        }
        return filterChainResolver;
    }

    protected FilterChainResolver createDefaultInstance() {
        // 获取过滤器的配置信息
        FilterConfig filterConfig = getFilterConfig();
        // 创建 PathMatchingFilterChainResolver
        if (filterConfig != null) {
            return new PathMatchingFilterChainResolver(filterConfig);
        } else {
            return new PathMatchingFilterChainResolver();
        }
    }

    protected void buildChains(FilterChainManager manager, Ini ini) {
        // 获取 ini 配置中的 [filters]
        Ini.Section section = ini.getSection(FILTERS);

        if (!CollectionUtils.isEmpty(section)) {
            String msg = "The [{}] section has been deprecated and will be removed in a future release!  Please " +
                    "move all object configuration (filters and all other objects) to the [{}] section.";
            log.warn(msg, FILTERS, IniSecurityManagerFactory.MAIN_SECTION_NAME);
        }

        Map<String, Object> defaults = new LinkedHashMap<String, Object>();
        // 获取默认的过滤器
        Map<String, Filter> defaultFilters = manager.getFilters();

        //now let's see if there are any object defaults in addition to the filters
        //these can be used to configure the filters:
        //create a Map of objects to use as the defaults:
        if (!CollectionUtils.isEmpty(defaultFilters)) {
            defaults.putAll(defaultFilters);
        }
        //User-provided objects must come _after_ the default filters - to allow the user-provided
        //ones to override the default filters if necessary.
        Map<String, ?> defaultBeans = getDefaults();
        if (!CollectionUtils.isEmpty(defaultBeans)) {
            defaults.putAll(defaultBeans);
        }

        // 获取所有的过滤器（包括配置的）
        Map<String, Filter> filters = getFilters(section, defaults);

        // 将所有的过滤器放入 FilterChainManager
        registerFilters(filters, manager);

        //urls section:
        section = ini.getSection(URLS);
        // 根据 [urls] 配置生成过滤链，最终放入 FilterChainManager 里
        createChains(section, manager);
    }

    protected void registerFilters(Map<String, Filter> filters, FilterChainManager manager) {
        if (!CollectionUtils.isEmpty(filters)) {
            boolean init = getFilterConfig() != null; //only call filter.init if there is a FilterConfig available
            for (Map.Entry<String, Filter> entry : filters.entrySet()) {
                String name = entry.getKey();
                Filter filter = entry.getValue();
                manager.addFilter(name, filter, init);
            }
        }
    }

    protected Map<String, Filter> getFilters(Map<String, String> section, Map<String, ?> defaults) {
        // 提取默认的过滤器
        Map<String, Filter> filters = extractFilters(defaults);

        if (!CollectionUtils.isEmpty(section)) {
            // TODO: 后续研究
            ReflectionBuilder builder = new ReflectionBuilder(defaults);
            Map<String, ?> built = builder.buildObjects(section);
            Map<String,Filter> sectionFilters = extractFilters(built);

            if (CollectionUtils.isEmpty(filters)) {
                filters = sectionFilters;
            } else {
                if (!CollectionUtils.isEmpty(sectionFilters)) {
                    filters.putAll(sectionFilters);
                }
            }
        }

        return filters;
    }

    /**
     * 提取过滤器
     *
     * @param objects
     * @return
     */
    private Map<String, Filter> extractFilters(Map<String, ?> objects) {
        if (CollectionUtils.isEmpty(objects)) {
            return null;
        }
        Map<String, Filter> filterMap = new LinkedHashMap<String, Filter>();
        for (Map.Entry<String, ?> entry : objects.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Filter) {
                filterMap.put(key, (Filter) value);
            }
        }
        return filterMap;
    }

    protected void createChains(Map<String, String> urls, FilterChainManager manager) {
        if (CollectionUtils.isEmpty(urls)) {
            if (log.isDebugEnabled()) {
                log.debug("No urls to process.");
            }
            return;
        }

        if (log.isTraceEnabled()) {
            log.trace("Before url processing.");
        }

        for (Map.Entry<String, String> entry : urls.entrySet()) {
            // 路径
            String path = entry.getKey();
            // 使用的过滤器
            String value = entry.getValue();
            // 创建过滤链
            manager.createChain(path, value);
        }
    }
}
