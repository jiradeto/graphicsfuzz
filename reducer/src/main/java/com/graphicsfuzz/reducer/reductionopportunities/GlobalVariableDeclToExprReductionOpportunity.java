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

package com.graphicsfuzz.reducer.reductionopportunities;

import com.graphicsfuzz.common.ast.TranslationUnit;
import com.graphicsfuzz.common.ast.decl.FunctionDefinition;
import com.graphicsfuzz.common.ast.decl.ScalarInitializer;
import com.graphicsfuzz.common.ast.decl.VariableDeclInfo;
import com.graphicsfuzz.common.ast.expr.BinOp;
import com.graphicsfuzz.common.ast.expr.BinaryExpr;
import com.graphicsfuzz.common.ast.expr.VariableIdentifierExpr;
import com.graphicsfuzz.common.ast.stmt.ExprStmt;
import com.graphicsfuzz.common.ast.visitors.VisitationDepth;

public class GlobalVariableDeclToExprReductionOpportunity extends AbstractReductionOpportunity {

  private final TranslationUnit tu;
  // The initialized global variable declaration info.
  private final VariableDeclInfo variableDeclInfo;

  GlobalVariableDeclToExprReductionOpportunity(VisitationDepth depth,
                                               TranslationUnit tu,
                                               VariableDeclInfo variableDeclInfo) {
    super(depth);
    this.tu = tu;
    this.variableDeclInfo = variableDeclInfo;
  }

  @Override
  void applyReductionImpl() {
    // Given the variable declaration info of global variable, we unset its initializer and
    // derive a new assignment statement which will be inserted as the first statement in
    // main function.
    assert variableDeclInfo.getInitializer() instanceof ScalarInitializer;
    FunctionDefinition mainFunction = tu.getMainFunction();
    final BinaryExpr binaryExpr = new BinaryExpr(
        new VariableIdentifierExpr(variableDeclInfo.getName()),
        ((ScalarInitializer) variableDeclInfo.getInitializer()).getExpr(),
        BinOp.ASSIGN
    );
    mainFunction.getBody().insertStmt(0, new ExprStmt(binaryExpr));
    variableDeclInfo.setInitializer(null);
  }

  @Override
  public boolean preconditionHolds() {
    return tu.hasMainFunction()
        && variableDeclInfo.hasInitializer();
  }
}
