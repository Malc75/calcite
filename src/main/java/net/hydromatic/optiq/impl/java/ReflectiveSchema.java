/*
// Licensed to Julian Hyde under one or more contributor license
// agreements. See the NOTICE file distributed with this work for
// additional information regarding copyright ownership.
//
// Julian Hyde licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except in
// compliance with the License. You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
*/
package net.hydromatic.optiq.impl.java;

import net.hydromatic.linq4j.*;
import net.hydromatic.linq4j.expressions.Expression;
import net.hydromatic.linq4j.expressions.Expressions;

import net.hydromatic.linq4j.expressions.FunctionExpression;
import net.hydromatic.optiq.*;

import org.eigenbase.reltype.RelDataType;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.*;

/**
 * Implementation of {@link net.hydromatic.optiq.Schema} that exposes the public
 * fields and methods in a Java object.
 */
public class ReflectiveSchema
    extends MapSchema
{
    final Class clazz;
    private Object target;

    /**
     * Creates a ReflectiveSchema.
     *
     * @param target Object whose fields will be sub-objects
     * @param typeFactory Type factory
     */
    public ReflectiveSchema(
        QueryProvider queryProvider,
        Object target,
        JavaTypeFactory typeFactory,
        Expression expression)
    {
        super(queryProvider, typeFactory, expression);
        this.clazz = target.getClass();
        this.target = target;
        for (Field field : clazz.getFields()) {
            tableMap.put(
                field.getName(),
                fieldRelation(field, typeFactory));
        }
        for (Method method : clazz.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            putMulti(
                membersMap,
                method.getName(),
                methodMember(method, typeFactory));
        }
    }

    public <T> TableFunction<T> methodMember(
        final Method method,
        final JavaTypeFactory typeFactory)
    {
        final ReflectiveSchema schema = this;
        final Type elementType = getElementType(method.getReturnType());
        final Class<?>[] parameterTypes = method.getParameterTypes();
        return new TableFunction<T>() {
            public String toString() {
                return "Member {method=" + method + "}";
            }

            public Type getElementType() {
                return elementType;
            }

            public List<Parameter> getParameters() {
                return new AbstractList<Parameter>() {
                    public Parameter get(final int index) {
                        return new Parameter() {
                            public int getOrdinal() {
                                return index;
                            }

                            public String getName() {
                                return "arg" + index;
                            }

                            public RelDataType getType() {
                                return typeFactory.createJavaType(
                                    parameterTypes[index]);
                            }
                        };
                    }

                    public int size() {
                        return parameterTypes.length;
                    }
                };
            }

            public Table<T> apply(final List<Object> arguments) {
                final List<Expression> list = new ArrayList<Expression>();
                for (Object argument : arguments) {
                    list.add(Expressions.constant(argument));
                }
                try {
                    final Object o = method.invoke(schema, arguments.toArray());
                    return new ReflectiveTable<T>(
                        schema,
                        elementType,
                        Expressions.call(
                            schema.getExpression(),
                            method,
                            list))
                    {
                        public Enumerator<T> enumerator() {
                            @SuppressWarnings("unchecked")
                            final Enumerable<T> enumerable = toEnumerable(o);
                            return enumerable.enumerator();
                        }
                    };
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                } catch (InvocationTargetException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    private <T> Table<T> fieldRelation(
        final Field field,
        JavaTypeFactory typeFactory)
    {
        final Type elementType = getElementType(field.getType());
        return new ReflectiveTable<T>(
            this,
            elementType,
            Expressions.field(
                ReflectiveSchema.this.getExpression(),
                field))
        {
            public String toString() {
                return "Relation {field=" + field.getName() + "}";
            }

            public Enumerator<T> enumerator() {
                try {
                    Object o = field.get(target);
                    @SuppressWarnings("unchecked")
                    Enumerable<T> enumerable1 = toEnumerable(o);
                    return enumerable1.enumerator();
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(
                        "Error while accessing field " + field, e);
                }
            }
        };
    }

    /** Deduces the element type of a collection;
     * same logic as {@link #toEnumerable} */
    private static Type getElementType(Class clazz) {
        if (clazz.isArray()) {
            return clazz.getComponentType();
        }
        if (Iterable.class.isInstance(clazz)) {
            return Object.class;
        }
        return null; // not a collection/array/iterable
    }

    private static Enumerable toEnumerable(Object o) {
        if (o.getClass().isArray()) {
            if (o instanceof Object[]) {
                return Linq4j.asEnumerable((Object[]) o);
            }
            // TODO: adapter for primitive arrays, e.g. float[].
            throw new UnsupportedOperationException();
        }
        if (o instanceof Iterable) {
            return Linq4j.asEnumerable((Iterable) o);
        }
        throw new RuntimeException(
            "Cannot convert " + o.getClass() + " into a Enumerable");
    }

    private static abstract class ReflectiveTable<T>
        extends Extensions.AbstractQueryable2<T>
        implements Table<T>
    {
        private final ReflectiveSchema schema;

        public ReflectiveTable(
            ReflectiveSchema schema,
            Type elementType,
            Expression expression)
        {
            super(schema.getQueryProvider(), elementType, expression);
            this.schema = schema;
        }

        public DataContext getDataContext() {
            return schema;
        }
    }
}

// End ReflectiveSchema.java
