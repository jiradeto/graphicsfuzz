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

import com.graphicsfuzz.common.ast.TranslationUnit;
import com.graphicsfuzz.common.ast.decl.FunctionDefinition;
import com.graphicsfuzz.common.ast.decl.FunctionPrototype;
import com.graphicsfuzz.common.ast.decl.Initializer;
import com.graphicsfuzz.common.ast.decl.ParameterDecl;
import com.graphicsfuzz.common.ast.decl.VariableDeclInfo;
import com.graphicsfuzz.common.ast.decl.VariablesDeclaration;
import com.graphicsfuzz.common.ast.expr.BinOp;
import com.graphicsfuzz.common.ast.expr.BinaryExpr;
import com.graphicsfuzz.common.ast.expr.Expr;
import com.graphicsfuzz.common.ast.expr.FunctionCallExpr;
import com.graphicsfuzz.common.ast.expr.VariableIdentifierExpr;
import com.graphicsfuzz.common.ast.stmt.BlockStmt;
import com.graphicsfuzz.common.ast.stmt.DeclarationStmt;
import com.graphicsfuzz.common.ast.stmt.ReturnStmt;
import com.graphicsfuzz.common.ast.stmt.Stmt;
import com.graphicsfuzz.common.ast.type.BasicType;
import com.graphicsfuzz.common.ast.type.Type;
import com.graphicsfuzz.common.util.IRandom;
import com.graphicsfuzz.common.util.IdGenerator;
import com.graphicsfuzz.common.util.PipelineInfo;
import com.graphicsfuzz.generator.semanticschanging.LiteralFuzzer;
import com.graphicsfuzz.util.Constants;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.lang3.tuple.Pair;

public class ExpressionGenerator {

  private static final int MAX_FUNCTION_PARAMS = 5;
  private static final int NUM_WAYS_TO_GENERATE_EXPR = 6;
  private static final int MAX_DEPTH = 5;
  // Theses MIN and Max are taken from the LiteralFuzzer, we may want to consider
  // modifying these values.
  private static final int INT_MIN = 0;
  private static final int INT_MAX = 1 << 17;

  private static final String UNKNOWN_VALUE = "_UNKNOWN_VALUE";
  private static final String NEGATIVE = "_NEGATIVE";
  private static final String ID = "_ID";

  private final TranslationUnit translationUnit;
  private final PipelineInfo pipelineInfo;
  private final IdGenerator idGenerator;
  private final IRandom generator;
  private int currentDepth;
  private final LiteralFuzzer literalFuzzer;
  private final FactManager globalFactManager;


  public ExpressionGenerator(TranslationUnit translationUnit, PipelineInfo pipelineInfo,
                             IRandom generator,
                             FactManager globalFactManager) {
    this.translationUnit = translationUnit;
    this.pipelineInfo = pipelineInfo;
    this.idGenerator = new IdGenerator();
    this.generator = generator;
    this.currentDepth = 0;
    this.literalFuzzer = new LiteralFuzzer(this.generator);
    this.globalFactManager = globalFactManager;
  }

  private Expr generateLiteralValue(Value value) {
    return value.generateLiteral(literalFuzzer);
  }

  /**
   * Given the expected value, this function will recursively declare variable declarations or
   * generate new functions whose results are known by the fact manager. If the given value is
   * empty, the fact manager can generate any arbitrary values that have the correct type.
   *
   * @param factManager        manager class holding the value and its associated expression that
   *                           guarantees to compute the given value.
   * @param functionDefinition a function into which the new expression will be injected.
   * @param stmt               statement in the body of the given function where the generated
   *                           expression will be inserted before.
   * @param value              the value that will be computed by the expression generated by
   *                           this method.
   * @return the expression whose value is known by the fact manager.
   */
  public Expr generateExpr(FactManager factManager,
                           FunctionDefinition functionDefinition,
                           Stmt stmt,
                           Value value) {

    if (currentDepth > MAX_DEPTH) {
      // When current depth has reached the maximum limitation, we don't want the program to go
      // deeper .Thus, we consider generating a new expression from non-recursive approaches only.
      // To do so, we will choose to either generating the expression by making the literal
      // value or calling the already declared variables retrieved from the fact manager.
      Expr result;
      do {
        if (generator.nextBoolean()) {
          result = generateLiteralValue(value);
        } else {
          result = generateKnownVariableFact(factManager, value);
        }
      } while (result == null);
      return result;
    }

    currentDepth++;
    Expr result;

    while (true) {
      switch (generator.nextInt(NUM_WAYS_TO_GENERATE_EXPR)) {
        case 0:
          result = generateLiteralValue(value);
          break;
        case 1:
          result = generateVariableFact(factManager, functionDefinition, stmt, value);
          break;
        case 2:
          result = generateKnownVariableFact(factManager, value);
          break;
        case 3:
          result = generateFunctionFact(factManager, functionDefinition, stmt, value);
          break;
        case 4:
          result = generateKnownFunctionFact(factManager, value, functionDefinition, stmt);
          break;
        case 5:
          result = generateAdditionExpression(factManager, functionDefinition, stmt, value);
          break;
        default:
          throw new RuntimeException("Should be unreachable as switch cases cover all cases");
      }
      if (result != null) {
        break;
      }
    }
    currentDepth--;
    return result;
  }

  private Expr generateAdditionExpression(FactManager factManager,
                                          FunctionDefinition functionDefinition,
                                          Stmt stmt, Value value) {
    if (!(value instanceof NumericValue)) {
      return null;
    }

    if (value.getType() != BasicType.INT && value.getType() != BasicType.FLOAT) {
      return null;
    }

    final Pair<Optional<Number>, Optional<Number>> pair = ((NumericValue) value).getPairSum(generator);
    return new BinaryExpr(
        generateExpr(factManager, functionDefinition, stmt,
            new NumericValue((BasicType) value.getType(), pair.getLeft())),
        generateExpr(factManager, functionDefinition, stmt,
            new NumericValue((BasicType) value.getType(), pair.getRight())),
        BinOp.ADD
    );
  }

  private Expr generateKnownFunctionFact(FactManager factManager,
                                         Value value,
                                         FunctionDefinition functionDefinition,
                                         Stmt stmt) {
    final Optional<List<FunctionFact>> functionFacts = globalFactManager.getFunctionFacts(value);
    if (!functionFacts.isPresent()) {
      return null;
    }
    final FunctionFact functionFact =
        functionFacts.get().get(generator.nextInt(functionFacts.get().size()));
    final List<Value> argValues = functionFact.getArguments();
    final List<Expr> args = generateFunctionArgs(factManager, functionDefinition, stmt,
        argValues);
    return new FunctionCallExpr(functionFact.getFunctionName(), args);
  }

  private Expr generateKnownVariableFact(FactManager factManager, Value value) {

    final Map<Value, List<VariableFact>> availableVarFacts = new HashMap<>();
    // As we have global fact manager holding the global variable facts and the current function
    // fact manager holding the local scope variable facts, we have to ensure that we retrieve all
    // available facts from both scopes.
    availableVarFacts.putAll(globalFactManager.getVariableFacts());
    availableVarFacts.putAll(factManager.getVariableFacts());

    final Optional<List<VariableFact>> applicableFacts = availableVarFacts.entrySet()
        .stream()
        .filter(item -> item.getKey().equals(value))
        .map(Map.Entry::getValue)
        .findFirst();

    if (!applicableFacts.isPresent()) {
      return null;
    }
    final VariableFact variableFact =
        applicableFacts.get().get(generator.nextInt(applicableFacts.get().size()));
    return new VariableIdentifierExpr(variableFact.getVariableName());
  }


  /**
   * Utility function to generate a set of expressions which will be used as the arguments of the
   * function call expression.
   *
   * @param factManager        manager class holding the value and its associated expression that
   *                           guarantees to compute the given value.
   * @param functionDefinition a function into which the new expression will be injected.
   * @param stmt               statement in the body of the given function where the generated
   *                           expression will be inserted before.
   * @param argValues          values of function arguments from which the new values being
   *                           generated are derived.
   * @return a list of function argument expressions.
   */
  private List<Expr> generateFunctionArgs(FactManager factManager,
                                          FunctionDefinition functionDefinition,
                                          Stmt stmt,
                                          List<Value> argValues) {
    return argValues.stream()
        .map(item -> generateExpr(
            factManager,
            functionDefinition,
            stmt,
            item
        )).collect(Collectors.toList());
  }

  private String freshId() {
    return ID + "_" + idGenerator.freshId();
  }

  private String genVarName(Value value, boolean isGlobal) {
    // Provides a variable name used when generating a new variable declaration from the given
    // value.
    // For example:
    // _GLF_PRIMITIVE_VAR_INT_NEGATIVE_103_0_ID_18: a variable of a value -103.
    // _GLF_PRIMITIVE_VAR_FLOAT_UNKNOWN_ID_69: a variable that can be any value of float type.
    return (isGlobal ? Constants.GLF_PRIMITIVE_GLOBAL_VAR : Constants.GLF_PRIMITIVE_VAR)
        + "_" + value.getType().toString().toUpperCase()
        + parseNameFromValue(value)
        + freshId();
  }

  private String genFunctionName(Value value) {
    // Provides a function name used when generating a new function fact from the given value.
    // For example:
    // _GLF_COMPUTE_FLOAT_1_0_ID_0: a function that returns a value 1.0 of float type.
    // _GLF_COMPUTE_VEC4_UNKNOWN_ID_1: a function that returns randomly generated value of vec4
    // type.
    return Constants.GLF_COMPUTE
        + "_" + value.getType().toString().toUpperCase()
        + parseNameFromValue(value)
        + freshId();
  }

  private String genParamName(Type type, boolean knownValue) {
    // Provides a parameter name required when making a paramater of a newly generated function.
    // For example:
    //  _GLF_PARAM_UNKNOWN_VALUE_VEC4_ID_1: a parameter of unknown value of vec4 type.
    //  _GLF_PARAM_INT_ID_62: a parameter of integer type.
    return (knownValue ? Constants.GLF_PARAM : Constants.GLF_PARAM_UNKNOWN_VALUE)
        + "_" + type.toString().toUpperCase()
        + freshId();
  }

  /**
   * Utility function to parse name from the value given, if value is known, for example, -0.45
   * will be parsed as NEGATIVE_0_45. If value is unknown, return the "UNKNOWN VALUE" string.
   *
   * @param value value that will be converted to the name of variables or functions.
   * @return a string derived from the given value which will be used as function or variable names.
   */
  private String parseNameFromValue(Value value) {
    if (!value.valueIsKnown()) {
      return UNKNOWN_VALUE;
    }
    final StringBuilder name = new StringBuilder();
    if (value instanceof NumericValue) {
      final NumericValue numericValue = (NumericValue) value;
      float floatValue = numericValue.getValue().get().floatValue();
      if (floatValue < 0.0) {
        name.append(NEGATIVE);
        floatValue = Math.abs(floatValue);
      }
      name.append("_");
      // Replace dot with underscore, i.e., 0.45 will be converted to 0_45.
      name.append(Float.toString(floatValue).replace(".", "_"));

    }
    if (value instanceof BooleanValue) {
      name.append("_").append(value.toString());
    }
    return name.toString();
  }

  private Expr generateVariableFact(FactManager factManager,
                                    FunctionDefinition currentFunction,
                                    Stmt stmt,
                                    Value value) {

    boolean atGlobalScope = factManager.isGlobalManager() || generator.nextBoolean();
    final String varName = genVarName(value, atGlobalScope);

    final VariableDeclInfo variableDeclInfo = new VariableDeclInfo(varName, null,
        new Initializer(generateExpr(atGlobalScope ? globalFactManager : factManager,
            currentFunction,
            stmt,
            value
        )));

    final VariablesDeclaration variablesDecl = new VariablesDeclaration(value.getType(),
        variableDeclInfo);
    final VariableDeclFact variableDeclFact = new VariableDeclFact(variablesDecl,
        variableDeclInfo, value);
    if (atGlobalScope) {
      translationUnit.addDeclarationBefore(variablesDecl, currentFunction);
      globalFactManager.addVariableFact(value, variableDeclFact);
    } else {
      currentFunction.getBody().insertBefore(stmt, new DeclarationStmt(variablesDecl));
      factManager.addVariableFact(value, variableDeclFact);
    }


    return new VariableIdentifierExpr(varName);
  }

  private Expr generateFunctionFact(FactManager factManager,
                                    FunctionDefinition currentFunction,
                                    Stmt stmt,
                                    Value value) {

    final String functionName = genFunctionName(value);

    final FactManager newFunctionScope = globalFactManager.newScope();
    final List<Value> argumentValues = new ArrayList<>();
    final List<ParameterDecl> parameterDecls = new ArrayList<>();

    final int noOfParams = generator.nextInt(MAX_FUNCTION_PARAMS);
    for (int i = 0; i < noOfParams; i++) {
      final Type paramType = getAvailableTypes().get(generator.nextInt(getAvailableTypes().size()));
      // Decide whether the value generated should be known by the fact manager.
      // If the fact manager is generating an unknown parameter(value is Optional.empty),
      // when calling this function the fact manager will generate any arbitrary value that
      // matches the parameter type.
      final Value paramValue = fuzzValue(paramType);
      final String paramName = genParamName(paramType, paramValue.valueIsKnown());

      argumentValues.add(paramValue);
      final ParameterDecl parameterDecl = new ParameterDecl(
          paramName,
          paramType,
          null
      );
      parameterDecls.add(parameterDecl);
      newFunctionScope.addVariableFact(paramValue, new ParameterDeclFact(parameterDecl,
          paramValue));
    }
    final BlockStmt body = new BlockStmt(Collections.emptyList(), false);
    final FunctionPrototype functionPrototype = new FunctionPrototype(functionName,
        value.getType(), parameterDecls);
    final FunctionDefinition newFunction = new FunctionDefinition(functionPrototype,
        body);
    translationUnit.addDeclarationBefore(newFunction, currentFunction);


    // Since the new function has an empty body, we first need to inject a return statement into
    // the body to be the point in a function where the new expressions can be injected before.
    final ReturnStmt returnStmt = new ReturnStmt(null);
    body.addStmt(returnStmt);
    returnStmt.setExpr(generateExpr(
        newFunctionScope,
        newFunction,
        returnStmt,
        value
    ));

    globalFactManager.addFunctionFact(value, new FunctionFact(functionPrototype, argumentValues,
        value));
    final List<Expr> args = generateFunctionArgs(factManager, currentFunction, stmt,
        argumentValues);
    return new FunctionCallExpr(functionName, args);
  }

  private List<? extends Type> getAvailableTypes() {
    return Arrays.asList(BasicType.BOOL, BasicType.INT, BasicType.FLOAT, BasicType.VEC2,
        BasicType.VEC3, BasicType.VEC4);
  }

  private Value fuzzValue(Type type) {
    // An Unknown value variable is the variable whose value could be anything. The fact manager
    // could generate any arbitrary value but with the correct type.
    final boolean isUnknown = generator.nextBoolean();
    if (type == BasicType.BOOL) {
      return new BooleanValue(
          isUnknown ? Optional.empty() : Optional.of(generator.nextBoolean()));
    }
    if (BasicType.allScalarTypes().contains(type)) {
      if (isUnknown) {
        return new NumericValue((BasicType) type, Optional.empty());
      }
      if (type == BasicType.INT) {
        final String intString = String.valueOf(generator.nextInt(INT_MAX - INT_MIN) + INT_MIN);
        return new NumericValue(BasicType.INT, Optional.of(Integer.valueOf(intString)));
      }
      if (type == BasicType.FLOAT) {
        final String floatString = randomFloatString();
        return new NumericValue(BasicType.FLOAT, Optional.of(Float.valueOf(floatString)));
      }
      if (type == BasicType.UINT) {
        final String intString = String.valueOf(generator.nextInt(INT_MAX - INT_MIN) + INT_MIN);
        return new NumericValue(BasicType.UINT, Optional.of(Math.abs(Integer.valueOf(intString))));
      }
      throw new RuntimeException("Not implemented yet!");
    }

    if (isUnknown) {
      return new CompositeValue(type, Optional.empty());
    }
    if (BasicType.allBasicTypes().contains(type)) {
      final List<Value> values = new ArrayList<>();
      for (int i = 0; i < ((BasicType) type).getNumElements(); i++) {
        values.add(fuzzValue(((BasicType) type).getElementType()));
      }
      return new CompositeValue(type, Optional.of(values));
    }

    // struct and array
    throw new RuntimeException("Not implemented yet!");
  }

  private String randomFloatString() {
    final int maxDigitsEitherSide = 5;
    StringBuilder sb = new StringBuilder();
    sb.append(generator.nextBoolean() ? "-" : "");
    int digitsBefore = Math.max(1, generator.nextInt(maxDigitsEitherSide));
    for (int i = 0; i < digitsBefore; i++) {
      int candidate;
      while (true) {
        candidate = generator.nextInt(10);
        if (candidate == 0 && i == 0 && digitsBefore > 1) {
          continue;
        }
        break;
      }
      sb.append(String.valueOf(candidate));
    }
    sb.append(".");
    for (int i = 0; i < digitsBefore; i++) {
      sb.append(String.valueOf(generator.nextInt(10)));
    }
    return sb.toString();
  }
}
