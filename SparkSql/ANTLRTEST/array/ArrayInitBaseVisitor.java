// Generated from ArrayInit.g4 by ANTLR 4.0
import org.antlr.v4.runtime.tree.*;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.ParserRuleContext;

public class ArrayInitBaseVisitor<T> extends AbstractParseTreeVisitor<T> implements ArrayInitVisitor<T> {
	@Override public T visitInit(ArrayInitParser.InitContext ctx) { return visitChildren(ctx); }

	@Override public T visitValue(ArrayInitParser.ValueContext ctx) { return visitChildren(ctx); }
}