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
package org.apache.ibatis.reflection.wrapper;

import org.apache.ibatis.reflection.MetaObject;

/**
 * @author Clinton Begin
 */
public interface ObjectWrapperFactory {

  /**
   * 对象包装器工厂是否可以生产传入对象的对象包装器
   * @param object 该对象是否可以由对象包装器工厂生产对象包装器
   * @return
   */
  boolean hasWrapperFor(Object object);

  /**
   * 生产一个对象包装器
   * @param metaObject 对象元数据
   * @param object 对象
   * @return
   */
  ObjectWrapper getWrapperFor(MetaObject metaObject, Object object);

}
