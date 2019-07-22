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

package com.graphicsfuzz.generator.tool;

import com.graphicsfuzz.common.ast.TranslationUnit;
import com.graphicsfuzz.common.ast.decl.FunctionDefinition;
import com.graphicsfuzz.common.ast.decl.FunctionPrototype;
import com.graphicsfuzz.common.ast.decl.PrecisionDeclaration;
import com.graphicsfuzz.common.ast.expr.BinOp;
import com.graphicsfuzz.common.ast.expr.BinaryExpr;
import com.graphicsfuzz.common.ast.expr.Expr;
import com.graphicsfuzz.common.ast.expr.FloatConstantExpr;
import com.graphicsfuzz.common.ast.expr.TypeConstructorExpr;
import com.graphicsfuzz.common.ast.expr.VariableIdentifierExpr;
import com.graphicsfuzz.common.ast.stmt.BlockStmt;
import com.graphicsfuzz.common.ast.stmt.ExprStmt;
import com.graphicsfuzz.common.ast.stmt.Stmt;
import com.graphicsfuzz.common.ast.type.BasicType;
import com.graphicsfuzz.common.ast.type.VoidType;
import com.graphicsfuzz.common.glslversion.ShadingLanguageVersion;
import com.graphicsfuzz.common.tool.PrettyPrinterVisitor;
import com.graphicsfuzz.common.util.GlslParserException;
import com.graphicsfuzz.common.util.IRandom;
import com.graphicsfuzz.common.util.ParseHelper;
import com.graphicsfuzz.common.util.ParseTimeoutException;
import com.graphicsfuzz.common.util.PipelineInfo;
import com.graphicsfuzz.common.util.RandomWrapper;
import com.graphicsfuzz.generator.mutateapi.ExpressionGenerator;
import com.graphicsfuzz.generator.mutateapi.FactManager;
import com.graphicsfuzz.generator.mutateapi.PrimitiveValue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import net.sourceforge.argparse4j.inf.ArgumentParserException;

public class ShaderGenerator {

  public static void mainHelper(String[] args) throws ArgumentParserException, IOException,
      ParseTimeoutException, InterruptedException, GlslParserException {

    final TranslationUnit tu = new TranslationUnit(Optional.of(ShadingLanguageVersion.ESSL_300),
        Arrays.asList(
            new PrecisionDeclaration("precision mediump float;"),
            new FunctionDefinition(
                new FunctionPrototype("main", VoidType.VOID, Collections.emptyList()),
                new BlockStmt(new ArrayList<>(), false))));

    final PipelineInfo pipelineInfo = new PipelineInfo();
    final IRandom generator = new RandomWrapper(0);
    final FactManager factManager = new FactManager(null);

    ExprStmt colorAssignment = new ExprStmt(null);
    ExpressionGenerator expressionGenerator = new ExpressionGenerator(tu, pipelineInfo);


    Expr rvalue = expressionGenerator.generateExpr(
        factManager,
        tu.getMainFunction(),
        colorAssignment,
        new PrimitiveValue(BasicType.FLOAT, Arrays.asList(Optional.of(0.46))),
        generator);

    Expr gvalue = expressionGenerator.generateExpr(
        factManager,
        tu.getMainFunction(),
        colorAssignment,
        new PrimitiveValue(BasicType.FLOAT, Arrays.asList(Optional.of(0.82))),
        generator);


    Expr bvalue = expressionGenerator.generateExpr(
        factManager,
        tu.getMainFunction(),
        colorAssignment,
        new PrimitiveValue(BasicType.FLOAT, Arrays.asList(Optional.of(0.15))),
        generator);

    Expr avalue = expressionGenerator.generateExpr(
        factManager,
        tu.getMainFunction(),
        colorAssignment,
        new PrimitiveValue(BasicType.FLOAT, Arrays.asList(Optional.of(1.0))),
        generator);

    colorAssignment.setExpr(new BinaryExpr(new VariableIdentifierExpr("_GLF_color"),
        new TypeConstructorExpr("vec4",
            rvalue,
            gvalue,
            bvalue,
            avalue
        ),
        BinOp.ASSIGN));

    tu.getMainFunction().getBody().addStmt(colorAssignment);
    System.out.println(PrettyPrinterVisitor.prettyPrintAsString(tu));
  }

  public static void main(String[] args) {
    try {
      mainHelper(args);
    } catch (ArgumentParserException exception) {
      exception.getParser().handleError(exception);
      System.exit(1);
    } catch (IOException | ParseTimeoutException | InterruptedException
        | GlslParserException exception) {
      exception.printStackTrace();
      System.exit(1);
    }
  }
}