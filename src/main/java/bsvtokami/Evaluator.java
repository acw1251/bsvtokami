package bsvtokami;

import org.antlr.v4.runtime.tree.AbstractParseTreeVisitor;
import org.antlr.v4.runtime.ParserRuleContext;
import java.util.*;
import java.util.logging.Logger;


class RuleNotReady extends RuntimeException {
    public RuleNotReady(String message) {
        super(message);
    }
}

/**
 * This class provides an empty implementation of {@link BSVVisitor},
 * which can be extended to create a visitor which only needs to handle a subset
 * of the available methods.
 *
 * @param <T> The return type of the visit operation. Use {@link Void} for
 * operations with no return type.
 */
public class Evaluator extends AbstractParseTreeVisitor<Value> implements BSVVisitor<Value> {
    private static Logger logger = Logger.getGlobal();
    private String modulename;
    private SymbolTable scope;
    private StaticAnalysis staticAnalyzer;
    private BSVTypeVisitor typeVisitor;
    private Stack<SymbolTable> scopeStack;
    private ArrayList<Rule> rules;
    private ArrayList<RegValue> registers;
    private boolean isElaborating = false;
    private boolean finishCalled = false;

    Evaluator(StaticAnalysis staticAnalyzer) {
        this.staticAnalyzer = staticAnalyzer;
        typeVisitor = new BSVTypeVisitor(staticAnalyzer);
        scopeStack = new Stack<>();
        rules = new ArrayList<>();
        registers = new ArrayList<>();
    }
    Evaluator(StaticAnalysis staticAnalyzer, BSVTypeVisitor typeVisitor) {
        this.staticAnalyzer = staticAnalyzer;
        this.typeVisitor = typeVisitor;
        scopeStack = new Stack<>();
        rules = new ArrayList<>();
        registers = new ArrayList<>();
    }

    public void evaluate(ParserRuleContext pkgdef) {
        visit(pkgdef);
    }

    public Value evaluateModule(String modulename, ParserRuleContext pkgdef) {
        visit(pkgdef);

        this.modulename = modulename;
        isElaborating = true;
        finishCalled = false;
        pushScope(pkgdef);
        logger.fine("evaluate module " + modulename + " scope " + scope);
        SymbolTableEntry entry = scope.lookup(modulename);
        logger.fine("evaluate module " + modulename + " scope " + scope + " entry " + entry
                           + " constructor "  + ((entry != null) ? entry.value : "<null entry>"));
        if (entry == null) {
            finishCalled = true;
            return new VoidValue();
        }

        FunctionValue constructor = (FunctionValue)entry.value;
        Value instance = instantiateModule(modulename, constructor);
        popScope();
        return instance;
    }

    Value evaluate(ParserRuleContext ctx, SymbolTable newScope) {
	assert newScope != null : "Evaluator.evaluate requires non-null scope";
	System.err.println("evaluate: scope " + newScope.name);
        pushScope(newScope);
        Value v = visit(ctx);
        popScope();
        return v;
    }

    boolean isFinished() {
        return finishCalled;
    }

    private void commitRegisters() {
        for (RegValue reg: registers)
            reg.commit();
    }

    private boolean isRuleReady(Rule rule) {
        if (rule.guard == null)
            return true;
        pushScope(rule);
        Value v = visit(rule.guard);
        popScope();
        BoolValue bv = (BoolValue)v;
        if (bv == null) {
            logger.fine("Expecting a BoolValue, got " + v);
            return false;
        }
        return bv.value;
    }

    boolean isMethodReady(FunctionValue mv) {
        BSVParser.MethoddefContext mc = mv.method;
        BSVParser.MethodcondContext methodcond = mc.methodcond();
        if (methodcond == null)
            return true;

        pushScope(mv.context);
        Value v = visit(methodcond.expression());
        popScope();
        BoolValue bv = (BoolValue)v;
        return bv.value;
    }

    public void runRule(Rule rule) {
        pushScope(rule);
        for (BSVParser.StmtContext stmt: rule.body) {
            visit(stmt);
        }
        popScope();
    }

    public int runRulesOnce() {
        isElaborating = false;
        int fire_count = 0;
        for (Rule rule: rules) {
            boolean ready = isRuleReady(rule);
            System.out.println(String.format("Rule %s %s", rule.name, (ready ? "ready" : "")));
            if (ready) {
                try {
                    runRule(rule);
                    commitRegisters();
                    fire_count += 1;
                } catch (RuleNotReady ex) {
                    logger.fine("Rule not ready " + ex);
                }
            }
        }
        return fire_count;
    }

    private void pushScope(ParserRuleContext ctx) {
        SymbolTable newScope = staticAnalyzer.getScope(ctx);
        logger.fine(String.format("pushScope { %s-%s", newScope.name, newScope));
        pushScope(newScope);
    }
    private void pushScope(Rule rule) {
        SymbolTable newScope = rule.context;
        logger.fine(String.format("pushScope rule %s{", rule.name));
        pushScope(newScope);
    }
    private void pushScope(SymbolTable newScope) {
	assert newScope != null : "Evaluator.pushScope requires non-null scope";
        logger.fine("Evaluator.pushScope " + newScope.name + " {");
        scopeStack.push(newScope);
        typeVisitor.pushScope(newScope);
        scope = newScope;
    }
    private void popScope() {
        typeVisitor.popScope();
        logger.fine("Evaluator.popScope " + scope.name + "}");
        scope = scopeStack.pop();
    }

    @Override protected Value aggregateResult(Value agg, Value nextResult) {
        //logger.fine("aggregate " + agg + " next " + nextResult);
        if (nextResult == null)
            return agg;
        else
            return nextResult;
    }

        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitPackagedef(BSVParser.PackagedefContext ctx) {
            pushScope(ctx);
            logger.fine("packagedef scope " + scope);
            Value v = new VoidValue();
	    for (BSVParser.PackagestmtContext stmt: ctx.packagestmt()) {
		v = visit(stmt);
		assert scope != null : String.format("Popped too many scopes evaluating line %s",
						     StaticAnalysis.sourceLocation(stmt));
	    }
            popScope();
            return v;
        }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitPackagedecl(BSVParser.PackagedeclContext ctx) {
            return visitChildren(ctx);
        }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitEndpackage(BSVParser.EndpackageContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitLowerCaseIdentifier(BSVParser.LowerCaseIdentifierContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitUpperCaseIdentifier(BSVParser.UpperCaseIdentifierContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitIdentifier(BSVParser.IdentifierContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitAnyidentifier(BSVParser.AnyidentifierContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitExportdecl(BSVParser.ExportdeclContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitExportitem(BSVParser.ExportitemContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitImportdecl(BSVParser.ImportdeclContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitImportitem(BSVParser.ImportitemContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitPackagestmt(BSVParser.PackagestmtContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
	@Override public Value visitPackageide(BSVParser.PackageideContext ctx) { return visitChildren(ctx); }
        @Override public Value visitInterfacedecl(BSVParser.InterfacedeclContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitInterfacememberdecl(BSVParser.InterfacememberdeclContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitMethodproto(BSVParser.MethodprotoContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitMethodprotoformals(BSVParser.MethodprotoformalsContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitMethodprotoformal(BSVParser.MethodprotoformalContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitSubinterfacedecl(BSVParser.SubinterfacedeclContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitTypedecl(BSVParser.TypedeclContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitTypedeftype(BSVParser.TypedeftypeContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitTypeformals(BSVParser.TypeformalsContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitTypeformal(BSVParser.TypeformalContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitTypedefsynonym(BSVParser.TypedefsynonymContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitTypedefenum(BSVParser.TypedefenumContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitTypedefenumelement(BSVParser.TypedefenumelementContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitTypedefstruct(BSVParser.TypedefstructContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitTypedeftaggedunion(BSVParser.TypedeftaggedunionContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitStructmember(BSVParser.StructmemberContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitUnionmember(BSVParser.UnionmemberContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitSubstruct(BSVParser.SubstructContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitSubunion(BSVParser.SubunionContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitDerives(BSVParser.DerivesContext ctx) { return visitChildren(ctx); }

    @Override public Value visitVarBinding(BSVParser.VarBindingContext ctx) {
	    try {
		for (BSVParser.VarinitContext varinit: ctx.varinit()) {
		    String varname = varinit.var.getText();
		    SymbolTableEntry entry = scope.lookup(varname);
		    assert entry != null : String.format("Could not find entry for %s at %s",
							 varname, StaticAnalysis.sourceLocation(ctx));
		    assert varinit.arraydims().expression().size() == 0
			: String.format("Unimplemented arraydims %s at %s",
					varinit.getText(),
					StaticAnalysis.sourceLocation(ctx));
		    entry.value = visit(varinit.rhs);
		}
	    } catch (Exception e) {
		logger.fine(String.format("ERROR: Failed to evaluate varbinding %s at %s: %s",
					  ctx.getText(), StaticAnalysis.sourceLocation(ctx), e));
		e.printStackTrace();
	    }
	    return new VoidValue();
        }

        @Override public Value visitActionBinding(BSVParser.ActionBindingContext ctx) {
            String var = ctx.var.getText();
            SymbolTableEntry entry = scope.lookup(var);
            logger.fine("action bind var " + var + " scope " + scope + " entry " + entry);
            Value v = null;
            if (ctx.rhs != null) {
                v = visit(ctx.rhs);
                logger.fine("  rhs " + ctx.rhs.getText() + " has value " + v);
            }
            if (isElaborating) {
                // module monad
                FunctionValue constructor = (FunctionValue)v;
                v = instantiateModule(constructor.name, constructor);
                entry.setValue(v);
                return v;
            } else {
                // action context
                v = v.read();
                entry.setValue(v);
                return v;
            }
        }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitLetBinding(BSVParser.LetBindingContext ctx) {
            String var = ctx.lowerCaseIdentifier().get(0).getText();
            SymbolTableEntry entry = scope.lookup(var);
            logger.fine("let var " + var + " scope " + scope + " entry " + entry);
            Value v = null;
            if (ctx.rhs != null) {
                v = visit(ctx.rhs);
                logger.fine("  " + ctx.getText() + " has value " + v);
                entry.setValue(v);
            }
            return v;
        }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitPatternBinding(BSVParser.PatternBindingContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitVarinit(BSVParser.VarinitContext ctx) {
            String var = ctx.var.getText();
            SymbolTableEntry entry = scope.lookup(var);
            logger.fine("var " + var + " scope " + scope + " entry " + entry);
            Value v = null;
            if (ctx.rhs != null) {
                v = visit(ctx.rhs);
                logger.fine("  " + ctx.getText() + " has value " + v);
                entry.setValue(v);
            } else {
                // undefined
            }
            return v;
        }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitArraydims(BSVParser.ArraydimsContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitTypeclassdecl(BSVParser.TypeclassdeclContext ctx) {
            // FIXME
            for (BSVParser.OverloadeddeclContext def: ctx.overloadeddecl()) {
                BSVParser.FunctionprotoContext functionproto = def.functionproto();
                if (functionproto != null) {
                    SymbolTable functionScope = staticAnalyzer.getScope(ctx);
                    String functionName = StaticAnalysis.unescape(functionproto.name.getText());
                    logger.fine("function " + functionName + " scope " + functionScope);
                    int argCount = (functionproto.methodprotoformals() != null) ? functionproto.methodprotoformals().methodprotoformal().size() : 0;
                    FunctionValue function = new FunctionValue(functionName, argCount, functionScope, scope);
                    scope.lookup(functionName).setValue(function);
                }
            }
            return new VoidValue();
        }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitTypeclasside(BSVParser.TypeclassideContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitTypedepends(BSVParser.TypedependsContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitTypedepend(BSVParser.TypedependContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitTypelist(BSVParser.TypelistContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitOverloadeddecl(BSVParser.OverloadeddeclContext ctx) { return visitChildren(ctx); }
        @Override public Value visitOverloadeddef(BSVParser.OverloadeddefContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitTctype(BSVParser.TctypeContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitTypeclassinstance(BSVParser.TypeclassinstanceContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitModuledef(BSVParser.ModuledefContext ctx) {
            String moduleName = ctx.moduleproto().name.getText();
	    logger.fine(String.format("Evaluating module def %s %s:%d",
				      moduleName,
				      ctx.start.getTokenSource().getSourceName(),
				      ctx.start.getLine()));
            SymbolTable moduleScope = staticAnalyzer.getScope(ctx); //.copy(scope);
            FunctionValue constructor = new FunctionValue(moduleName, ctx, moduleScope, scope);
            SymbolTableEntry entry = scope.lookup(moduleName);
	    assert entry != null : String.format("failed to find symbol table entry for %s", moduleName);
            entry.value = constructor;
            return constructor;
        }

    public Value instantiateModule(String instanceName, FunctionValue constructor) {
	assert constructor != null;
        logger.fine("Instantiating module " + constructor.name);
        if (constructor.name.equals("mkReg")) {
            RegValue reg = new RegValue(instanceName, constructor.args.get(0));
            registers.add(reg);
            return reg;
        }
        ModuleInstance instance = new ModuleInstance(instanceName,
                                                     constructor.module,
                                                     constructor.context //.copy(constructor.parentFrame)
                                                     );
        pushScope(constructor.module);
        for (BSVParser.ModulestmtContext stmt: constructor.module.modulestmt()) {
            Value v = visit(stmt);
        }
        popScope();
        //BSVParser.ModuledefContext moduledef = constructor.module;
        return instance;
    }

        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitModuleproto(BSVParser.ModuleprotoContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitModulestmt(BSVParser.ModulestmtContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitModuleinst(BSVParser.ModuleinstContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitModuleapp(BSVParser.ModuleappContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitModuleactualparamarg(BSVParser.ModuleactualparamargContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitMethoddef(BSVParser.MethoddefContext ctx) {
            SymbolTable methodScope = staticAnalyzer.getScope(ctx);
            String methodName = ctx.name.getText();
            logger.fine("method " + methodName + " scope " + methodScope);
            FunctionValue function = new FunctionValue(methodName, ctx, methodScope, scope);
            scope.lookup(methodName).setValue(function);
            return function;
        }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitMethodformals(BSVParser.MethodformalsContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitMethodformal(BSVParser.MethodformalContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitMethodcond(BSVParser.MethodcondContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitSubinterfacedef(BSVParser.SubinterfacedefContext ctx) { return visitChildren(ctx); }

        @Override public Value visitRuledef(BSVParser.RuledefContext ctx) {
            Rule rule = new Rule(ctx.name.getText(), ctx, scope);
            rules.add(rule);
            pushScope(ctx);
            popScope();
            return rule;
        }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitRulecond(BSVParser.RulecondContext ctx) { return visitChildren(ctx); }
	@Override public Value visitRulebody(BSVParser.RulebodyContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitFunctiondef(BSVParser.FunctiondefContext ctx) {
            SymbolTable functionScope = staticAnalyzer.getScope(ctx);
            String functionName = StaticAnalysis.unescape(ctx.functionproto().name.getText());
            logger.fine("function " + functionName + " scope " + functionScope);
            FunctionValue function = new FunctionValue(functionName, ctx, functionScope, scope);
            SymbolTableEntry entry = scope.lookup(functionName);
	    if (entry == null)
		logger.fine(ctx.functionproto().getText());
	    assert entry != null : String.format("No entry for %s at %s", functionName, StaticAnalysis.sourceLocation(ctx));
	    entry.setValue(function);
            return function;
        }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitFunctionproto(BSVParser.FunctionprotoContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitExterncimport(BSVParser.ExterncimportContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitExterncfuncargs(BSVParser.ExterncfuncargsContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitExterncfuncarg(BSVParser.ExterncfuncargContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitVarassign(BSVParser.VarassignContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitLvalue(BSVParser.LvalueContext ctx) {
            if (ctx.lvalue() == null) {
                String varName = ctx.getText();
                SymbolTableEntry entry = scope.lookup(varName);
                return entry.value;
            }
            Value lvalue = visit(ctx.lvalue());
            if (ctx.index != null) {
                return lvalue.sub(visit(ctx.index).read());
            } else if (ctx.lsb != null) {
                return lvalue.sub(visit(ctx.msb).read(), visit(ctx.lsb).read());
            } else if (ctx.lowerCaseIdentifier() != null) {
                logger.fine("Error: Unhandled field access: " + ctx.getText());
                return null;
            }
            return lvalue;
        }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitBsvtype(BSVParser.BsvtypeContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitTypeide(BSVParser.TypeideContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitTypenat(BSVParser.TypenatContext ctx) { return visitChildren(ctx); }
        @Override public Value visitOperatorexpr(BSVParser.OperatorexprContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitMatchesexpr(BSVParser.MatchesexprContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitCaseexpr(BSVParser.CaseexprContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitCondexpr(BSVParser.CondexprContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
	@Override public Value visitTripleandexpr(BSVParser.TripleandexprContext ctx) { return visitChildren(ctx); }
        @Override public Value visitCaseexpritem(BSVParser.CaseexpritemContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
	@Override public Value visitPatterncond(BSVParser.PatterncondContext ctx) { return visitChildren(ctx); }
        @Override public Value visitBinopexpr(BSVParser.BinopexprContext ctx) {
            if (ctx.left == null)
                return visit(ctx.unopexpr());
            logger.fine("visitBinop " + ctx.getText());
            Value left = visit(ctx.left).read();
            Value right = visit(ctx.right).read();
            String op = ctx.op.getText();
            logger.fine(String.format("    %s %s %s", left, op, right));
            return left.binop(op, right);
        }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitUnopexpr(BSVParser.UnopexprContext ctx) {
	    Value v = visit(ctx.exprprimary());
            if (ctx.op == null) {
                return v;
            } else {
                return v.unop(ctx.op.getText());
            }
        }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitBitconcat(BSVParser.BitconcatContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitVarexpr(BSVParser.VarexprContext ctx) {
            //FIXME package name
            String varName = ctx.anyidentifier().getText();
            SymbolTableEntry entry;
            if (ctx.pkg != null) {
                String varPackageName = ctx.pkg.getText();
                entry = staticAnalyzer.lookup(varPackageName, varName);
            } else {
                entry = scope.lookup(varName);
            }
            logger.fine("var '" + varName + "' entry " + entry + " " + scope + " parent " + scope.parent);
            if (entry != null)
                logger.fine("    entry.value " + entry.value);
            return entry.value;
        }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitBlockexpr(BSVParser.BlockexprContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitStructexpr(BSVParser.StructexprContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitStringliteral(BSVParser.StringliteralContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitRulesexpr(BSVParser.RulesexprContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitIntliteral(BSVParser.IntliteralContext ctx) {
            return new IntValue(ctx.IntLiteral().getText());
        }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitRealliteral(BSVParser.RealliteralContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitCastexpr(BSVParser.CastexprContext ctx) {
            Value v = visit(ctx.exprprimary());
            BSVType bsvtype = typeVisitor.visit(ctx.bsvtype());
            return v.cast(bsvtype);
        }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitResetbyexpr(BSVParser.ResetbyexprContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitUndefinedexpr(BSVParser.UndefinedexprContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitClockedbyexpr(BSVParser.ClockedbyexprContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitReturnexpr(BSVParser.ReturnexprContext ctx) {
            Value v = visit(ctx.expression());
            logger.fine("return (" + ctx.expression().getText() + ") = " + v);
            return v;
        }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitFieldexpr(BSVParser.FieldexprContext ctx) {
            Value v = visit(ctx.exprprimary());
            String fieldName = ctx.field.getText();
            logger.fine("field expr " + v + " . " + fieldName);
            ModuleInstance instance = (ModuleInstance)v;
            SymbolTableEntry entry = instance.context.lookup(fieldName);
            if (entry != null) {
                logger.fine("  method " + entry.value);
                return entry.value;
            }
            return v;
        }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitParenexpr(BSVParser.ParenexprContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitInterfaceexpr(BSVParser.InterfaceexprContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitActionblockexpr(BSVParser.ActionblockexprContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitParfsmexpr(BSVParser.ParfsmexprContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitCallexpr(BSVParser.CallexprContext ctx) {
	    Value fcn = visit(ctx.fcn);
            FunctionValue closure = (FunctionValue)fcn;
            if(closure == null) {
                System.err.println("ERROR: " + ctx.fcn.getText() + " value " + fcn + " at " + StaticAnalysis.sourceLocation(ctx.fcn));
                return new IntValue("0");
                //return new VoidValue();
            }
            assert closure != null : ctx.fcn.getText() + " value " + fcn + " at " + StaticAnalysis.sourceLocation(ctx.fcn);
            ArrayList<Value> argValues = new ArrayList<>();
            for (BSVParser.ExpressionContext argExpr: ctx.expression()) {
                argValues.add(visit(argExpr));
            }
            if (closure.name.equals("$methodready")) {
                FunctionValue mv = (FunctionValue)argValues.get(0);
                return new BoolValue (isMethodReady(mv));
            }
            if (closure.name.equals("$finish")) {
                finishCalled = true;
                return new VoidValue();
            }
            if (closure.name.equals("$vecnew")) {
                IntValue mv = (IntValue)argValues.get(0);
                assert mv != null : ctx.expression(0).getText();
                return new VectorValue(mv.value);
            }
            if (argValues.size() < closure.remainingArgCount()) {
                FunctionValue newClosure = closure.copy();
                newClosure.args.addAll(argValues);
                return newClosure;
            }
            ParserRuleContext defcontext = (closure.function != null) ? closure.function : closure.method;
            SymbolTable functionScope = staticAnalyzer.getScope(defcontext);
            logger.fine("calling " + closure.name + " fcn (" + closure.name + ") scope " + scope);
            //functionScope = functionScope.copy(closure.parentFrame);
            pushScope(defcontext);
	    if (closure.provisos() != null) {
		assert closure.provisos() == null
		    : String.format("Need to evaluate provisos %s at %s",
				    closure.provisos().getText(),
				    StaticAnalysis.sourceLocation(closure.provisos()));
	    }
            if (argValues.size() > 0) {
                List<String> formalVars = (closure.function != null)
                    ? getFormalVars(closure.function)
                    : getFormalVars(closure.method);
                int argnum = 0;
                for (Value argValue: argValues) {
                    String varName = formalVars.get(argnum);
                    SymbolTableEntry entry = scope.lookup(varName);
                    if (entry == null) {
                        logger.fine("Did not find entry for function " + closure.name + " var " + varName);
                    }
                    entry.value = argValue;
                    argnum += 1;
                }
            }
            Value v = new VoidValue();
            if (closure.function != null) {
                if (closure.function.expression() != null) {
                    v = visit(closure.function.expression());
                } else {
                    for (BSVParser.StmtContext stmt: closure.function.stmt()) {
                        v = visit(stmt);
                    }
                }
            } else {
                boolean ready = isMethodReady(closure);
                if (!ready)
                    throw new RuleNotReady(closure.name);
                if (closure.method.expression() != null) {
                    v = visit(closure.method.expression());
                } else {
                    for (BSVParser.StmtContext stmt: closure.method.stmt()) {
                        v = visit(stmt);
                    }
                }
            }
            popScope();
            return v;
        }

    long log2(long x) {
	long logx = 0;
	while (x > 1) {
	    x /= 2;
	    logx += 1;
	}
	return logx;
    }
    long exp2(long x) {
	long exp = 1;
	while (x > 0) {
	    x -= 1;
	    exp *= 2;
	}
	return exp;
    }

    BSVType evaluateType(BSVType bsvtype) {
	System.err.println("evaluateType " + bsvtype + " in scope " + scope.name);
	logger.fine("evaluateType " + bsvtype + " in scope " + scope.name);
	typeVisitor.pushScope(scope);
	bsvtype = typeVisitor.dereferenceTypedef(bsvtype);
	typeVisitor.popScope();
	if (bsvtype.name.equals("TLog")) {
	    assert bsvtype.params.size() == 1;
	    BSVType paramtype = evaluateType(bsvtype.params.get(0));
	    logger.fine("TLog " + paramtype);
	    if (paramtype.numeric) {
		long v = paramtype.asLong();
		long log2v = log2(v);
		logger.fine(String.format("log2(%d) = %d", v, log2v));
		return new BSVType(log2v);
	    }
	} else if (bsvtype.name.equals("TExp")) {
	    assert bsvtype.params.size() == 1;
	    BSVType paramtype = evaluateType(bsvtype.params.get(0));
	    logger.fine("TExp " + paramtype);
	    if (paramtype.numeric) {
		long v = paramtype.asLong();
		long exp2v = exp2(v);
		logger.fine(String.format("exp2(%d) = %d", v, exp2v));
		return new BSVType(exp2v);
	    }
	} else if (bsvtype.name.equals("TDiv")) {
	    assert bsvtype.params.size() == 2;
	    BSVType numtype = evaluateType(bsvtype.params.get(0));
	    BSVType denomtype = evaluateType(bsvtype.params.get(1));
	    long div = numtype.asLong() / denomtype.asLong();
	    logger.fine(String.format("TDiv(%d, %d) = %d", numtype.asLong(), denomtype.asLong(), div));
	    return new BSVType(div);
	}
	return bsvtype;
    }

        @Override public Value visitValueofexpr(BSVParser.ValueofexprContext ctx) {
            assert ctx.bsvtype() != null;
            BSVType bsvtype = typeVisitor.visit(ctx.bsvtype());
            assert bsvtype != null : ctx.bsvtype().getText();
            bsvtype = bsvtype.prune();
	    bsvtype = evaluateType(bsvtype);
            if(bsvtype.isVar) {
		System.err.println("ERROR: " + String.format("%s has type %s at %s",
				ctx.getText(), bsvtype, StaticAnalysis.sourceLocation(ctx)));
                return new IntValue(0);
            }
            assert !bsvtype.isVar
		: String.format("%s has type %s at %s",
				ctx.getText(), bsvtype, StaticAnalysis.sourceLocation(ctx));
	    logger.fine(String.format("eval valueOf(%s) with type %s at %s",
					     ctx.bsvtype().getText(), bsvtype, StaticAnalysis.sourceLocation(ctx)));
            return new IntValue((int)Long.parseLong(bsvtype.name));
        }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitSeqfsmexpr(BSVParser.SeqfsmexprContext ctx) { return visitChildren(ctx); }
        @Override public Value visitTaggedunionexpr(BSVParser.TaggedunionexprContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitArraysub(BSVParser.ArraysubContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitActionvalueblockexpr(BSVParser.ActionvalueblockexprContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitTypeassertionexpr(BSVParser.TypeassertionexprContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitMemberbinds(BSVParser.MemberbindsContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitMemberbind(BSVParser.MemberbindContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitInterfacestmt(BSVParser.InterfacestmtContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitRulesstmt(BSVParser.RulesstmtContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitBeginendblock(BSVParser.BeginendblockContext block) {
            pushScope(block);

            logger.fine("entering block scope " + scope + " {");
            Value v = null;
            for (BSVParser.StmtContext stmt: block.stmt()) {
                v = visit(stmt);
            }
            logger.fine("} exited block");

            popScope();
            return v;
        }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitActionblock(BSVParser.ActionblockContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitActionvalueblock(BSVParser.ActionvalueblockContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitRegwrite(BSVParser.RegwriteContext ctx) {
            Value lhs = visit(ctx.lhs);
            Value rhs = visit(ctx.rhs).read();
            RegValue reg = (RegValue)lhs;
            System.out.println(String.format("Updating reg %s/%s with value %s", ctx.lhs.getText(), reg, rhs));
            reg.update(rhs);
            return rhs;
        }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitStmt(BSVParser.StmtContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitIfstmt(BSVParser.IfstmtContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitCasestmt(BSVParser.CasestmtContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitCasestmtitem(BSVParser.CasestmtitemContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitCasestmtpatitem(BSVParser.CasestmtpatitemContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitCasestmtdefaultitem(BSVParser.CasestmtdefaultitemContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitWhilestmt(BSVParser.WhilestmtContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitForstmt(BSVParser.ForstmtContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitForinit(BSVParser.ForinitContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitForoldinit(BSVParser.ForoldinitContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitSimplevarassign(BSVParser.SimplevarassignContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitFornewinit(BSVParser.FornewinitContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitSimplevardeclassign(BSVParser.SimplevardeclassignContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitFortest(BSVParser.FortestContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitForincr(BSVParser.ForincrContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitVarincr(BSVParser.VarincrContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitPattern(BSVParser.PatternContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitConstantpattern(BSVParser.ConstantpatternContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitTaggedunionpattern(BSVParser.TaggedunionpatternContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitStructpattern(BSVParser.StructpatternContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitTuplepattern(BSVParser.TuplepatternContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitAttributeinstance(BSVParser.AttributeinstanceContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitAttrspec(BSVParser.AttrspecContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitProvisos(BSVParser.ProvisosContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitProviso(BSVParser.ProvisoContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitFsmstmt(BSVParser.FsmstmtContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitSeqfsmstmt(BSVParser.SeqfsmstmtContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitParfsmstmt(BSVParser.ParfsmstmtContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitIffsmstmt(BSVParser.IffsmstmtContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitReturnfsmstmt(BSVParser.ReturnfsmstmtContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitWhilefsmstmt(BSVParser.WhilefsmstmtContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitForfsminit(BSVParser.ForfsminitContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitForfsmstmt(BSVParser.ForfsmstmtContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitRepeatfsmstmt(BSVParser.RepeatfsmstmtContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitLoopbodyfsmstmt(BSVParser.LoopbodyfsmstmtContext ctx) { return visitChildren(ctx); }
	@Override public Value visitPortide(BSVParser.PortideContext ctx) {
	    assert false : "Unused";
	    return null;
	}
        @Override public Value visitImportbvi(BSVParser.ImportbviContext ctx) {
            String moduleName = ctx.moduleproto().name.getText();
	    logger.fine(String.format("Evaluating module def %s %s:%d",
				      moduleName,
				      ctx.start.getTokenSource().getSourceName(),
				      ctx.start.getLine()));
            SymbolTable moduleScope = staticAnalyzer.getScope(ctx); //.copy(scope);
	    int argCount = (ctx.moduleproto().methodprotoformals() == null) ? 0 : ctx.moduleproto().methodprotoformals().methodprotoformal().size();
	    // fixme
            FunctionValue constructor = new FunctionValue(moduleName, argCount, moduleScope, scope);
            SymbolTableEntry entry = scope.lookup(moduleName);
	    assert entry != null : String.format("failed to find symbol table entry for %s", moduleName);
            entry.value = constructor;
	    return constructor;
	}
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitBvistmt(BSVParser.BvistmtContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitBviportopt(BSVParser.BviportoptContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitBvimethodopt(BSVParser.BvimethodoptContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitBvimethodname(BSVParser.BvimethodnameContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitBvimethodnames(BSVParser.BvimethodnamesContext ctx) { return visitChildren(ctx); }
        /**
         * {@inheritDoc}
         *
         * <p>The default implementation returns the result of calling
         * {@link #visitChildren} on {@code ctx}.</p>
         */
        @Override public Value visitBvischedule(BSVParser.BvischeduleContext ctx) { return visitChildren(ctx); }

    List<String> getFormalVars(BSVParser.FunctiondefContext function) {
        ArrayList<String> formalVars = new ArrayList<>();
                BSVParser.MethodprotoformalsContext formals = function.functionproto().methodprotoformals();
                int argnum = 0;

                for (BSVParser.MethodprotoformalContext formal: formals.methodprotoformal()) {
                    String varName;
                    if (formal.name != null)
                        varName = formal.name.getText();
                    else
                        varName = formal.functionproto().name.getText();
                    formalVars.add(varName);
                }
            return formalVars;
    }
    List<String> getFormalVars(BSVParser.MethoddefContext method) {
        ArrayList<String> formalVars = new ArrayList<>();
        BSVParser.MethodformalsContext formals = method.methodformals();
        int argnum = 0;
        for (BSVParser.MethodformalContext formal : formals.methodformal()) {
            String varName;
            if (formal.name != null)
                varName = formal.name.getText();
            else
                varName = formal.functionproto().name.getText();
            formalVars.add(varName);
        }
        return formalVars;
    }
}
