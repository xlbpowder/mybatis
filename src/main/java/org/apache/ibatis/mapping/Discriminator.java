/**
 *    Copyright 2009-2019 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.mapping;

import org.apache.ibatis.session.Configuration;

import java.util.Collections;
import java.util.Map;

/**
 * 鉴别器
 *<pre>
 *   {@code
 *   <discriminator javaType="int" column="vehicle_type">
 *     <case value="1" resultMap="carResult"/>
 *     <case value="2" resultMap="truckResult"/>
 *     <case value="3" resultMap="vanResult"/>
 *     <case value="4" resultMap="suvResult"/>
 *   </discriminator>
 *   }
 *</pre>
 *
 * @author Clinton Begin
 */
public class Discriminator {

    /**
     * ResultMapping 对象
     */
    private ResultMapping resultMapping;
    /**
     * 集合。即注释上的 N 条示例
     *
     * KEY ：value 属性
     * VALUE ：resultMap 属性
     */
    private Map<String, String> discriminatorMap;

    Discriminator() {
    }

    public static class Builder {

        private Discriminator discriminator = new Discriminator();

        /**
         * 设置{@code resultMapping}和{@code discriminatorMap}到成员{@link #discriminator}的{@link Discriminator#resultMapping}和{@link Discriminator#discriminatorMap}
         *
         * @param configuration
         * @param resultMapping
         * @param discriminatorMap
         */
        public Builder(Configuration configuration, ResultMapping resultMapping, Map<String, String> discriminatorMap) {
            discriminator.resultMapping = resultMapping;
            discriminator.discriminatorMap = discriminatorMap;
        }

        /**
         * <ol>
         *     <li>
         *         assert 成员变量{@link #discriminator}的{@link Discriminator#resultMapping}不为null、{@link Discriminator#discriminatorMap}不为null也不empty
         *     </li>
         *     <li>
         *         将成员变量{@link #discriminator}的{@link Discriminator#discriminatorMap}利用{@link Collections#unmodifiableMap(Map)}转为不可更改的{@link Map}
         *     </li>
         *     <li>
         *         返回成员变量{@link #discriminator}
         *     </li>
         * </ol>
         *
         * @return
         */
        public Discriminator build() {
            assert discriminator.resultMapping != null;
            assert discriminator.discriminatorMap != null;
            assert !discriminator.discriminatorMap.isEmpty();
            discriminator.discriminatorMap = Collections.unmodifiableMap(discriminator.discriminatorMap);
            return discriminator;
        }
    }

    public ResultMapping getResultMapping() {
        return resultMapping;
    }

    public Map<String, String> getDiscriminatorMap() {
        return discriminatorMap;
    }

    public String getMapIdFor(String s) {
        return discriminatorMap.get(s);
    }

}
