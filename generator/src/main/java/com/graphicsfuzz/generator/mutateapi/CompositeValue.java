/*
 * Copyright 2019 The GraphicsFuzz Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.graphicsfuzz.generator.mutateapi;

import com.graphicsfuzz.common.ast.expr.Expr;
import com.graphicsfuzz.common.ast.expr.TypeConstructorExpr;
import com.graphicsfuzz.common.ast.type.BasicType;
import com.graphicsfuzz.common.ast.type.Type;
import com.graphicsfuzz.generator.semanticschanging.LiteralFuzzer;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class CompositeValue implements Value {
  private final Type type;
  private final Optional<List<Value>> valueList;

  public CompositeValue(Type type, Optional<List<Value>> valueList) {
    this.type = type;
    this.valueList = valueList;
  }

  public Optional<List<Value>> getValueList() {
    return valueList;
  }

  @Override
  public Type getType() {
    return type;
  }

  @Override
  public boolean valueIsUnknown() {
    return !valueList.isPresent();
  }

  @Override
  public boolean equals(Object that) {
    if (this == that) {
      return true;
    }

    if (!(that instanceof CompositeValue)) {
      return false;
    }

    final CompositeValue thatCompositeValue = (CompositeValue) that;
    return this.getType() == thatCompositeValue.getType()
        && this.getValueList() == ((CompositeValue) that).getValueList();
  }

  @Override
  public int hashCode() {
    int hashCode = 17;

    hashCode = 37 * hashCode + getType().hashCode();

    if (!valueIsUnknown()) {
      hashCode = 37 * hashCode + getValueList().hashCode();
    }
    return hashCode;
  }

  @Override
  public Expr generateLiteral(LiteralFuzzer literalFuzzer) {
    if (valueIsUnknown()) {
      return literalFuzzer.fuzz(type).orElse(null);
    }

    if (type instanceof BasicType) {
      final List<Expr> args = valueList
          .get()
          .stream()
          .map(item -> item.generateLiteral(literalFuzzer))
          .collect(Collectors.toList());
      return new TypeConstructorExpr(type.toString(), args);
    }

    // TODO: implement other types (Struct and Array)
    throw new RuntimeException("The given type is not supported");
  }

  @Override
  public String toString() {
    return valueIsUnknown() ? "unknown_composite" : valueList.get().toString();
  }
}
