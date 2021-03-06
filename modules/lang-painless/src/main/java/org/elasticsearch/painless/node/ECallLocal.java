/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.painless.node;

import org.elasticsearch.painless.Globals;
import org.elasticsearch.painless.Locals;
import org.elasticsearch.painless.Locals.LocalMethod;
import org.elasticsearch.painless.Location;
import org.elasticsearch.painless.MethodWriter;
import org.elasticsearch.painless.lookup.PainlessBinding;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Method;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.elasticsearch.painless.WriterConstants.CLASS_TYPE;

/**
 * Represents a user-defined call.
 */
public final class ECallLocal extends AExpression {

    private final String name;
    private final List<AExpression> arguments;

    private LocalMethod method = null;
    private PainlessBinding binding = null;

    public ECallLocal(Location location, String name, List<AExpression> arguments) {
        super(location);

        this.name = Objects.requireNonNull(name);
        this.arguments = Objects.requireNonNull(arguments);
    }

    @Override
    void extractVariables(Set<String> variables) {
        for (AExpression argument : arguments) {
            argument.extractVariables(variables);
        }
    }

    @Override
    void analyze(Locals locals) {
        method = locals.getMethod(name, arguments.size());


        if (method == null) {
            binding = locals.getPainlessLookup().lookupPainlessBinding(name, arguments.size());

            if (binding == null) {
                throw createError(new IllegalArgumentException("Unknown call [" + name + "] with [" + arguments.size() + "] arguments."));
            }
        }

        List<Class<?>> typeParameters = new ArrayList<>(method == null ? binding.typeParameters : method.typeParameters);

        for (int argument = 0; argument < arguments.size(); ++argument) {
            AExpression expression = arguments.get(argument);

            expression.expected = typeParameters.get(argument);
            expression.internal = true;
            expression.analyze(locals);
            arguments.set(argument, expression.cast(locals));
        }

        statement = true;
        actual = method == null ? binding.returnType : method.returnType;
    }

    @Override
    void write(MethodWriter writer, Globals globals) {
        writer.writeDebugInfo(location);

        if (method == null) {
            String name = globals.addBinding(binding.javaConstructor.getDeclaringClass());
            Type type = Type.getType(binding.javaConstructor.getDeclaringClass());
            int javaConstructorParameterCount = binding.javaConstructor.getParameterCount();

            Label nonNull = new Label();

            writer.loadThis();
            writer.getField(CLASS_TYPE, name, type);
            writer.ifNonNull(nonNull);
            writer.loadThis();
            writer.newInstance(type);
            writer.dup();

            for (int argument = 0; argument < javaConstructorParameterCount; ++argument) {
                arguments.get(argument).write(writer, globals);
            }

            writer.invokeConstructor(type, Method.getMethod(binding.javaConstructor));
            writer.putField(CLASS_TYPE, name, type);

            writer.mark(nonNull);
            writer.loadThis();
            writer.getField(CLASS_TYPE, name, type);

            for (int argument = 0; argument < binding.javaMethod.getParameterCount(); ++argument) {
                arguments.get(argument + javaConstructorParameterCount).write(writer, globals);
            }

            writer.invokeVirtual(type, Method.getMethod(binding.javaMethod));
        } else {
            for (AExpression argument : arguments) {
                argument.write(writer, globals);
            }

            writer.invokeStatic(CLASS_TYPE, new Method(method.name, method.methodType.toMethodDescriptorString()));
        }
    }

    @Override
    public String toString() {
        return singleLineToStringWithOptionalArgs(arguments, name);
    }
}
