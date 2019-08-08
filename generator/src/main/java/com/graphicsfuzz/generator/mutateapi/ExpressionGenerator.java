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
import com.graphicsfuzz.common.ast.expr.UnOp;
import com.graphicsfuzz.common.ast.expr.UnaryExpr;
import com.graphicsfuzz.common.ast.expr.VariableIdentifierExpr;
import com.graphicsfuzz.common.ast.stmt.BlockStmt;
import com.graphicsfuzz.common.ast.stmt.DeclarationStmt;
import com.graphicsfuzz.common.ast.stmt.ExprStmt;
import com.graphicsfuzz.common.ast.stmt.ForStmt;
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
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

public class ExpressionGenerator {

  private static final int MAX_FUNCTION_PARAMS = 5;
  private static final int NUM_WAYS_TO_GENERATE_EXPR = 7;
  private static final int MAX_DEPTH = 5;
  // Theses boundaries are taken from the LiteralFuzzer, we may want to consider
  // modifying these values.
  private static final int INT_MIN = 0;
  private static final int INT_MAX = 1 << 17;

  private static final String NEGATIVE = "_NEGATIVE";
  private static final String ID = "_id";

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
      // deeper. Thus, we consider generating a new expression from non-recursive approaches only.
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
          result = generateAdditionValue(factManager, functionDefinition, stmt, value);
          break;
        case 6:
          result = generateForLoopValue(factManager, functionDefinition, stmt, value);
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

  /**
   * A method that provides a value obtained by executing a for loop.
   *
   * <p>Given the expected value, this method will declare a new zero-initialized variable and a for
   * loop statement guarantee to make the variable value equals to the given value. To do so, it
   * will generate a random number(divisor) that will be added to the variable for each iteration.
   * The binary expression adding the remainder to the return value will also be
   * injected if there is a left over from a division of original value by the divisor.
   *
   * <p>For example, given x value we can derive a for loop statement and a remainder from the
   * equation x = (divisor * iterations) + remainder.
   *
   * <p>The code fragment below is an example result obtained by this method which returns an int 7.
   * int _GLF_PRIMITIVE_int_7 = 0;
   * // 3 is the number of iterations obtained by dividing the given value 7 by the random
   * divisor 2.
   * for(int i = 0; i < 3; i ++) {
   * _GLF_PRIMITIVE_int_7 += 2;   // 2 is a divisor which is randomly generated.
   * }
   * _GLF_PRIMITIVE_int_7 += 1;     // 1 is a remainder after dividing 7 by 2.
   *
   * @param factManager        manager class holding the value and its associated expression that
   *                           guarantees to compute the given value.
   * @param functionDefinition a function into which the new expression will be injected.
   * @param stmt               statement in the body of the given function where the generated
   *                           expression will be inserted before.
   * @param value              the value that will be computed by the expression generated by
   *                           this method.
   * @return a variable identifier to the new variable generated by this method.
   */

  private Expr generateForLoopValue(FactManager factManager,
                                    FunctionDefinition functionDefinition,
                                    Stmt stmt, Value value) {
    if (value.getType() != BasicType.INT) {
      return null;
    }
    // If the global fact manager is given, the caller expect the generated expression to be
    // available at the global scope. However the new for loop statement generated by this
    // method will be injected into the body of a given function making it available only in
    // the local scope. Hence, if the global scope expression is requested, null is returned.
    if (factManager.globalScope()) {
      return null;
    }

    // Declare and initialize a new zero-value variable.
    final String varName = genVarName(value, false);
    final VariableDeclInfo variableDeclInfo = new VariableDeclInfo(varName, null,
        new Initializer(
            generateExpr(factManager,
                functionDefinition,
                stmt,
                new NumericValue(BasicType.INT, Optional.of(0)))
        ));
    final VariablesDeclaration variablesDecl = new VariablesDeclaration(value.getType(),
        variableDeclInfo);
    functionDefinition.getBody().insertBefore(stmt, new DeclarationStmt(variablesDecl));

    // Decide whether the loop should be incremented or decremented for each iteration.
    final boolean isIncrement = generator.nextBoolean();
    // If value is unknown, we could generate and return any number.
    int original = (int) ((NumericValue) value).getValue().orElse(generator.nextInt(INT_MAX));

    // if the original value is zero, calling random wrapper will thrown an error illegal argument
    // exception.
    if (original < 1) {
      return null;
    }

    // A random number that will be added to the newly declared variable on each iteration.
    // We use max function here to prevent the division by zero error.
    int divisor = Math.max(1, generator.nextInt(original));


    final int iterations = original / divisor;
    // A left over number that will be added after for loop is executed. The binary
    // expression responsible to add a remainder to the variable will be inserted after for
    // loop statement.
    final int remainder = original % divisor;

    // Values of the numbers which will be used for the for loop.
    final Value divisorValue = new NumericValue(BasicType.INT, Optional.of(divisor));
    final Value iterationValue = new NumericValue(BasicType.INT,
        Optional.of(isIncrement ? 0 : iterations));
    final Value conditionValue = new NumericValue(BasicType.INT,
        Optional.of(isIncrement ? iterations : 0));

    final Stmt init = new DeclarationStmt(new VariablesDeclaration(BasicType.INT,
        new VariableDeclInfo("i", null,
            new Initializer(generateExpr(factManager, functionDefinition, stmt, iterationValue))))
    );
    final Expr condition = new BinaryExpr(new VariableIdentifierExpr("i"),
        generateExpr(factManager, functionDefinition, stmt, conditionValue),
        isIncrement ? BinOp.LT : BinOp.GE);
    final Expr increment = new UnaryExpr(new VariableIdentifierExpr("i"),
        isIncrement ? UnOp.POST_INC : UnOp.POST_DEC);
    final Stmt body = new BlockStmt(Arrays.asList(new ExprStmt(new BinaryExpr(
        new VariableIdentifierExpr(varName),
        generateExpr(
            factManager,
            functionDefinition,
            stmt,
            divisorValue),
        BinOp.ADD_ASSIGN
    ))), false);
    final ForStmt forStmt = new ForStmt(init, condition, increment, body);
    functionDefinition.getBody().insertBefore(stmt, forStmt);

    if (remainder > 0) {
      final Value remainderValue = new NumericValue((BasicType) value.getType(),
          Optional.of(remainder));
      functionDefinition.getBody().insertBefore(stmt, new ExprStmt(new BinaryExpr(
          new VariableIdentifierExpr(varName),
          generateExpr(factManager, functionDefinition, stmt, remainderValue),
          BinOp.ADD_ASSIGN
      )));
    }

    return new VariableIdentifierExpr(varName);
  }

  private Expr generateAdditionValue(FactManager factManager,
                                     FunctionDefinition functionDefinition,
                                     Stmt stmt, Value value) {
    if (!(value instanceof NumericValue)) {
      return null;
    }

    if (value.getType() != BasicType.INT && value.getType() != BasicType.FLOAT) {
      return null;
    }

    final Pair<Optional<Number>, Optional<Number>> pair = getPairSum(value);
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
    final List<FunctionFact> availableFacts = factManager.getFunctionFacts(value);
    if (availableFacts.isEmpty()) {
      return null;
    }
    final FunctionFact functionFact = availableFacts.get(generator.nextInt(availableFacts.size()));
    final List<Value> argValues = functionFact.getArguments();
    final List<Expr> args = generateFunctionArgs(factManager, functionDefinition, stmt,
        argValues);
    return new FunctionCallExpr(functionFact.getFunctionName(), args);
  }

  private Expr generateKnownVariableFact(FactManager factManager, Value value) {
    final List<VariableFact> availableFacts = factManager.getVariableFacts(value);
    if (availableFacts.isEmpty()) {
      return null;
    }
    final VariableFact variableFact = availableFacts.get(generator.nextInt(availableFacts.size()));
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
    // _GLF_PRIMITIVE_int_NEGATIVE_103_0_id_18: a variable of a value -103.
    // _GLF_PRIMITIVE_float_UNKNOWN_id_69: a variable that can be any value of float type.
    return (isGlobal ? Constants.GLF_PRIMITIVE_GLOBAL : Constants.GLF_PRIMITIVE)
        + "_" + value.getType().toString()
        + parseNameFromValue(value)
        + freshId();
  }

  private String genFunctionName(Value value) {
    // Provides a function name used when generating a new function fact from the given value.
    // For example:
    // _GLF_COMPUTE_float_1_0_id_0: a function that returns a value 1.0 of float type.
    // _GLF_COMPUTE_vec4_UNKNOWN_id_1: a function that returns randomly generated value of vec4
    // type.
    return Constants.GLF_COMPUTE
        + "_" + value.getType().toString()
        + parseNameFromValue(value)
        + freshId();
  }

  private String genParamName(Type type, boolean unknownValue) {
    // Provides a parameter name required when making a paramater of a newly generated function.
    // For example:
    //  _GLF_PARAM_UNKNOWN_VALUE_VEC4_id_1: a parameter of unknown value of vec4 type.
    //  _GLF_PARAM_INT_id_62: a parameter of integer type.
    return (unknownValue ? Constants.GLF_PARAM_UNKNOWN_VALUE : Constants.GLF_PARAM)
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
    if (value.valueIsUnknown()) {
      return "_" + value.toString();
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

    boolean atGlobalScope = factManager.globalScope() || generator.nextBoolean();
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
      final String paramName = genParamName(paramType, paramValue.valueIsUnknown());

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
    // We will have to consider supporting more types but at the moment as we are using
    // LiteralFuzzer to generate literal expressions so we are unable to cover all basic type now.
    // The following are types currently supported by LiteralFuzzer.
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

    // TODO: Support struct and array type.
    throw new RuntimeException("Not implemented yet!");
  }

  /**
   * This function returns a pair of two numbers whose sum is equal to the given value.
   *
   * @param value the original value that will be split into two numbers.
   * @return if value is unknown, returns a pair of empty value. Otherwise find and return two
   * numbers that will add up to the given value.
   */
  public Pair<Optional<Number>, Optional<Number>> getPairSum(Value value) {

    assert value instanceof NumericValue;
    final NumericValue numericValue = (NumericValue) value;

    if (numericValue.valueIsUnknown()) {
      return new ImmutablePair<>(Optional.empty(), Optional.empty());
    }

    // Following the equation a = (a/b) + (a - a/b), we first need to pick a random integer 'b'
    // in range 1-10. We then derive the left number by dividing the original number 'a' with 'b'.
    // We then generate the right number by subtracting the original value 'a' with the left number
    // obtained from the previous step.
    //
    // For example, if a number 7 is an input and we pick a random integer 3. The left number
    // is 7 divide by 3 which equals to 2. Next we subtract the original value with the left number
    // to find the right number: 7 - 2 = 5. We finally have two numbers 2 and 5 that can add
    // up to 7.

    if (numericValue.getType() == BasicType.FLOAT) {
      final float a = numericValue.getValue().get().floatValue();
      final float b = Math.max(1, generator.nextInt(10));
      final float left = a / b;
      final float right = a - left;
      return new ImmutablePair<>(Optional.of(left), Optional.of(right));
    }

    if (numericValue.getType() == BasicType.INT) {
      final int a = numericValue.getValue().get().intValue();
      final int b = Math.max(1, generator.nextInt(10));
      final int left = a / b;
      final int right = a - left;
      return new ImmutablePair<>(Optional.of(left), Optional.of(right));
    }

    throw new RuntimeException("Should be unreachable");
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
