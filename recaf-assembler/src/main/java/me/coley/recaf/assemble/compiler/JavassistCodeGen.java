package me.coley.recaf.assemble.compiler;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.bytecode.Bytecode;
import javassist.compiler.JvstCodeGen;

/**
 * Modified code generator for Javassist to pull information from Recaf.
 *
 * @author Matt Coley
 */
public class JavassistCodeGen extends JvstCodeGen {
	/**
	 * @param classSupplier
	 * 		Class information supplier.
	 * @param bytecode
	 * 		Target generated bytecode wrapper.
	 * @param declaringClass
	 * 		Class containing the method our expression resides in.
	 * @param classPool
	 * 		Class pool to use.
	 */
	public JavassistCodeGen(ClassSupplier classSupplier, Bytecode bytecode, CtClass declaringClass, ClassPool classPool) {
		super(bytecode, declaringClass, classPool);
		setTypeChecker(new JavassistTypeChecker(classSupplier, declaringClass, classPool, this));
		resolver = new JavassistMemberResolver(classSupplier, classPool);
	}
}