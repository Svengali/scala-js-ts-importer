/* TypeScript importer for Scala.js
 * Copyright 2013-2014 LAMP/EPFL
 * @author  Sébastien Doeraene
 */

package org.scalajs.tools.tsimporter

import Trees.{TypeRef => TypeRefTree, _}
import org.scalajs.tools.tsimporter.sc.TypeRef.Singleton
import sc._

import scala.collection.immutable.{AbstractSeq, LinearSeq}
import scala.collection.mutable.ListBuffer

/** The meat and potatoes: the importer
 *  It reads the TypeScript AST and produces (hopefully) equivalent Scala
 *  code.
 */
class Importer(val output: java.io.PrintWriter, config: Config) {
  import Importer._

  /** Entry point */
  def apply(declarations: List[DeclTree]): Unit = {
    val rootPackage = new PackageSymbol(Name.EMPTY)

    for (declaration <- declarations)
      processDecl(rootPackage, declaration)

    new Printer(output, config).printSymbol(rootPackage, None)
  }

  private def processDecl(owner: ContainerSymbol, declaration: DeclTree): Unit = {
    declaration match {
      case ModuleDecl(PropertyNameName(name), innerDecls) =>
        assert(owner.isInstanceOf[PackageSymbol],
            s"Found package $name in non-package $owner")
        val sym = owner.asInstanceOf[PackageSymbol].getPackageOrCreate(name)

        for (innerDecl <- innerDecls)
          processDecl(sym, innerDecl)

      case TopLevelExportDecl(IdentName(name)) =>
        // print nothing, since the value specified by the identifier is printed elsewhere.

      case VarDecl(IdentName(name), Some(tpe @ ObjectType(members))) =>
        val sym = owner.getModuleOrCreate(name)
        processMembersDecls(owner, sym, members)

      case ConstDecl(IdentName(name), Some(tpe @ ObjectType(members))) =>
        val sym = owner.getModuleOrCreate(name)
        processMembersDecls(owner, sym, members)

      case LetDecl(IdentName(name), Some(tpe @ ObjectType(members))) =>
        val sym = owner.getModuleOrCreate(name)
        processMembersDecls(owner, sym, members)

      case TypeDecl(TypeNameName(name), tpe @ ObjectType(members)) =>
        val sym = owner.getClassOrCreate(name)
        processMembersDecls(owner, sym, members)

      case EnumDecl(TypeNameName(name), members) =>
        // Type
        val tsym = owner.getClassOrCreate(name)
        tsym.isSealed = true

        // Module
        val sym = owner.getModuleOrCreate(name)
        for (IdentName(name) <- members) {
          val m = sym.newField(name, Set.empty)
          m.protectName()
          m.tpe = TypeRef(tsym.name)
        }
        val applySym = sym.newMethod(Name("apply"), Set.empty[Modifier])
        applySym.params += new ParamSymbol(Name("value"), TypeRef(tsym.name))
        applySym.resultType = TypeRef.String
        applySym.isBracketAccess = true

      case ClassDecl(TypeNameName(name), tparams, parent, implements, members, isAbstract) =>
        val sym = owner.getClassOrCreate(name)
        sym.isAbstract = isAbstract
        sym.isTrait = false
        parent.foreach(sym.parents += typeToScala(_))
        for {
          parent <- implements.map(typeToScala)
          if !sym.parents.contains(parent)
        } {
          sym.parents += parent
        }
        sym.tparams ++= typeParamsToScala(tparams)
        processMembersDecls(owner, sym, members)
        if (!sym.members.exists(_.name == Name.CONSTRUCTOR)) {
          processDefDecl(sym, Name.CONSTRUCTOR,
              FunSignature(Nil, Nil, Some(TypeRefTree(CoreType("void")))), Set.empty[Modifier], inherited = false)
        }

      case InterfaceDecl(TypeNameName(name), tparams, inheritance, members) =>
        val sym = owner.getClassOrCreate(name)
        for {
          parent <- inheritance.map(typeToScala)
          if !sym.parents.contains(parent)
        } {
          sym.parents += parent
        }
        sym.tparams ++= typeParamsToScala(tparams)
        processMembersDecls(owner, sym, members)
        processFactory(owner, sym, members)

      case TypeAliasDecl(TypeNameName(name), tparams, alias) =>
        val sym = owner.newTypeAlias(name)
        alias match {
          case tpe @ ObjectType(members) =>
            val anonymousName = s"${ name.name }Object"
            val anonyousClass = owner.getClassOrCreate(name.copy(name = anonymousName))
            anonyousClass.tparams ++= typeParamsToScala(tparams)
            processMembersDecls(owner, anonyousClass, members)
            sym.tparams ++= typeParamsToScala(tparams)
            sym.alias = typeToScala(Trees.TypeRef(TypeName(anonymousName), tparams.map(p => Trees.TypeRef(p.name))))
          case tpe @ UnionType(_, _) if config.generateTypeAliasEnums =>
            getLiterals(tpe) match {
              case Seq() =>
                sym.tparams ++= typeParamsToScala(tparams)
                sym.alias = typeToScala(alias)
              case nonEmpty =>
                val traitDef = owner.getClassOrCreate(name)
                traitDef.parents.clear()
                traitDef.parents.addOne(TypeRef.Any)
                traitDef.isSealed = true
                val moduleDef = owner.getModuleOrCreate(name)
                moduleDef.isGlobal = false

                nonEmpty.foreach { c =>
                  val (nameStr, lit) = c match {
                    case Undefined() => ("Undefined", "js.undefined")
                    case Null() => ("Null", "null")
                    case BooleanLiteral(value) => (value.toString, value.toString)
                    case IntLiteral(value) => (value.toString, value.toString)
                    case DoubleLiteral(value) => (value.toString, value.toString)
                    case StringLiteral(value) => (value, s""""${value}"""")
                  }
                  val f = moduleDef.newField(
                    Name(nameStr),
                    modifiers = Set(
                      Modifier.Const,
                      Modifier.Inline,
                    )
                  )
                  f.tpe = TypeRef(name)
                  f.rhs = Some(s"""$lit.asInstanceOf[${name}]""")
                }
                owner.removeTypeAlias(name)
            }

          case _ =>
            sym.tparams ++= typeParamsToScala(tparams)
            sym.alias = typeToScala(alias)
        }

      case VarDecl(IdentName(name), TypeOrAny(tpe)) =>
        val sym = owner.newField(name, Set.empty)
        sym.tpe = typeToScala(tpe)

      case ConstDecl(IdentName(name), TypeOrAny(tpe)) =>
        val sym = owner.newField(name, Set(Modifier.Const))
        sym.tpe = typeToScala(tpe)

      case LetDecl(IdentName(name), TypeOrAny(tpe)) =>
        val sym = owner.newField(name, Set(Modifier.ReadOnly))
        sym.tpe = typeToScala(tpe)

      case FunctionDecl(IdentName(name), signature) =>
        processDefDecl(owner, name, signature, Set.empty[Modifier], inherited = false)

      case ImportDecl => // Ignore imports

      case _ =>
        owner.members += new CommentSymbol("??? "+declaration)
    }
  }

  private def getLiterals(union: UnionType): Seq[Literal] = {
    union match {
      case UnionType(ConstantType(x), ConstantType(y)) => Seq(x, y)
      case UnionType(u @ UnionType(_, _), ConstantType(x)) => getLiterals(u) ++ Seq(x)
      case _ => Seq.empty
    }
  }

  private def processFactory(owner: ContainerSymbol, sym: Symbol, members: List[Trees.MemberTree]): Unit = {
    if (config.factoryConfig.generateFactory && members.forall(_.isInstanceOf[PropertyMember])) {
      val module = owner.getModuleOrCreate(sym.name)
      module.isGlobal = false
    }
  }
  
  private def processMembersDecls(enclosing: ContainerSymbol,
      owner: ContainerSymbol, members: List[MemberTree]): Unit = {

    val OwnerName = owner.name

    lazy val companionClassRef = {
      val tparams = enclosing.findClass(OwnerName) match {
        case Some(clazz) =>
          clazz.tparams.toList.map(tp => TypeRefTree(TypeNameName(tp.name), Nil))
        case _ => Nil
      }
      TypeRefTree(TypeNameName(OwnerName), tparams)
    }

    for (member <- members) member match {
      case CallMember(signature) =>
        processDefDecl(owner, Name("apply"), signature, Set.empty[Modifier], inherited = false, protectName = false, ownerOuter = Some(enclosing))

      case ConstructorMember(sig @ FunSignature(tparamsIgnored, params, Some(resultType)))
      if owner.isInstanceOf[ModuleSymbol] && resultType == companionClassRef =>
        val classSym = enclosing.getClassOrCreate(owner.name)
        classSym.isTrait = false
        processDefDecl(classSym, Name.CONSTRUCTOR,
            FunSignature(Nil, params, Some(TypeRefTree(CoreType("void")))), Set.empty[Modifier], inherited = false, ownerOuter = Some(enclosing))

      case PropertyMember(PropertyNameName(name), opt, tpe, mods) if mods(Modifier.Static) =>
        assert(owner.isInstanceOf[ClassSymbol],
            s"Cannot process static member $name in module definition")
        val module = enclosing.getModuleOrCreate(owner.name)
        processPropertyDecl(enclosing, module, name, tpe, mods, optional = opt)

      case PropertyMember(PropertyNameName(name), opt, tpe, mods) =>
        val inherited = owner.isInstanceOf[ClassSymbol] && {
          val parents = owner.asInstanceOf[ClassSymbol].parents.map { ref =>
            enclosing.getClassOrCreate(ref.typeName.last)
          }
          parents.flatMap(_.members.collect {
            case sym: FieldSymbol => sym
          }).exists(_.name == name)
        }
        processPropertyDecl(enclosing, owner, name, tpe, mods, optional = opt, inherited = inherited)

      case FunctionMember(PropertyName("constructor"), _, signature, modifiers)
          if owner.isInstanceOf[ClassSymbol] && !modifiers(Modifier.Static) =>
        owner.asInstanceOf[ClassSymbol].isTrait = false
        processDefDecl(owner, Name.CONSTRUCTOR,
            FunSignature(Nil, signature.params, Some(TypeRefTree(CoreType("void")))), modifiers, inherited = false, ownerOuter = Some(enclosing))

      case FunctionMember(PropertyNameName(name), opt, signature, modifiers)
          if modifiers(Modifier.Static) =>
        assert(owner.isInstanceOf[ClassSymbol],
            s"Cannot process static member $name in module definition")
        val module = enclosing.getModuleOrCreate(owner.name)
        processDefDecl(module, name, signature, modifiers, inherited = false, optional = opt, ownerOuter = Some(enclosing))

      case FunctionMember(PropertyNameName(name), opt, signature, modifiers) if owner.isInstanceOf[ClassSymbol] =>
        val parents = owner.asInstanceOf[ClassSymbol].parents.map { ref =>
          enclosing.getClassOrCreate(ref.typeName.last)
        }
        val inherited = parents.flatMap(_.members.collect {
          case sym: MethodSymbol => sym
        }).exists { m =>
          m.name == name && m.modifiers == modifiers && 
          signature.params.map(fp => {
            val psym = new ParamSymbol(Name(fp.name.name))
            psym.tpe = typeToScala(fp.tpe.get)
            psym.optional = fp.optional
            psym
          }) == m.params.toList
        }
        processDefDecl(owner, name, signature, modifiers, inherited = inherited, ownerOuter = Some(enclosing))

      case FunctionMember(PropertyNameName(name), opt, signature, modifiers) =>
        processDefDecl(owner, name, signature, modifiers, inherited = false, ownerOuter = Some(enclosing))

      case IndexMember(IdentName(indexName), indexType, valueType, modifiers) =>
        val indexTpe = typeToScala(indexType)
        val valueTpe = typeToScala(valueType)

        val getterSym = owner.newMethod(Name("apply"), Set.empty[Modifier])
        getterSym.params += new ParamSymbol(indexName, indexTpe)
        getterSym.resultType = valueTpe
        getterSym.isBracketAccess = true

        if (!modifiers(Modifier.ReadOnly)){
          val setterSym = owner.newMethod(Name("update"), Set.empty[Modifier])
          setterSym.params += new ParamSymbol(indexName, indexTpe)
          setterSym.params += new ParamSymbol(Name("v"), valueTpe)
          setterSym.resultType = TypeRef.Unit
          setterSym.isBracketAccess = true
        }

      case PrivateMember => // ignore

      case _ =>
        owner.members += new CommentSymbol("??? "+member)
    }
  }

  private def processPropertyDecl(enclosing: ContainerSymbol, owner: ContainerSymbol, name: Name,
      tpe: TypeTree, modifiers: Modifiers, protectName: Boolean = true, optional: Boolean = true, inherited: Boolean = false): Unit = {
    if (name.name != "prototype") {
      tpe match {
        case ObjectType(members) if members.forall(_.isInstanceOf[CallMember]) =>
          // alternative notation for overload methods - #3
          for (CallMember(signature) <- members)
            processDefDecl(owner, name, signature, modifiers, protectName)
        case ObjectType(members) =>
          val module = enclosing.getModuleOrCreate(owner.name)
          module.isGlobal = false
          val classSym = module.getClassOrCreate(name.capitalize)
          processMembersDecls(module, classSym, members)
          val sym = owner.newField(name, modifiers)
          sym.inherited = inherited
          val underlying = TypeRef(QualifiedName(module.name, classSym.name))
          sym.tpe = if (optional) TypeRef(QualifiedName.UndefOr, List(underlying)) else underlying
          processFactory(module, classSym, members)
        case _ =>
          val mods = owner match {
            case sym: ClassSymbol if config.forceAbstractFieldOnTrait && sym.isTrait =>
              sym.isAbstract = true
              modifiers + Modifier.Abstract
            case _ => modifiers
          }
          val sym = owner.newField(name, mods)
          sym.inherited = inherited
          if (protectName)
            sym.protectName()
          val underlying = typeToScala(tpe)
          sym.tpe = if (optional) TypeRef(QualifiedName.UndefOr, List(underlying)) else underlying
      }
    }
  }

  private def processDefDecl(owner: ContainerSymbol, 
                             name: Name, 
                             signature: FunSignature, 
                             modifiers: Modifiers,
                             inherited: Boolean,
                             protectName: Boolean = true,
                             optional: Boolean = true, 
                             ownerOuter: Option[ContainerSymbol] = None): Unit = {
    val mods = owner match {
      case sym: ClassSymbol if config.forceAbstractFieldOnTrait && sym.isTrait =>
        sym.isAbstract = true
        modifiers + Modifier.Abstract
      case _ => modifiers
    }
    val sym = owner.newMethod(name, mods)
    if (protectName)
      sym.protectName()
    
    sym.tparams ++= typeParamsToScala(signature.tparams)
    sym.inherited = inherited
    for (FunParam(IdentName(paramName), opt, TypeOrAny(tpe)) <- signature.params) {
      val paramSym = new ParamSymbol(paramName)
      paramSym.optional = opt
      tpe match {
        case RepeatedType(tpe0) =>
          // TS1047: Rest parameter cannot be optional
          paramSym.tpe = TypeRef.Repeated(typeToScala(tpe0))
        case _ @ ObjectType(members) =>
          val container = owner match {
            case symbol: ClassSymbol => ownerOuter.get
            case _ => owner
          }
          val anonymousName = name match {
            case Name.CONSTRUCTOR => s"${owner.name.capitalize}Constructor${paramName.capitalize}"
            case _ => s"${name.capitalize}${paramName.capitalize}"
          }
          val anonyousClass = container.getClassOrCreate(name.copy(name = anonymousName))
          processMembersDecls(container, anonyousClass, members)
          paramSym.tpe = typeToScala(Trees.TypeRef(TypeName(anonymousName)))
        case _ =>
          paramSym.tpe = typeToScala(tpe)
      }
      sym.params += paramSym
    }

    sym.resultType = typeToScala(signature.resultType.orDynamic, true)

    owner.removeIfDuplicate(sym)
  }

  private def typeParamsToScala(tparams: List[TypeParam]): List[TypeParamSymbol] = {
    for (TypeParam(TypeNameName(tparam), upperBound) <- tparams) yield
      new TypeParamSymbol(tparam, upperBound map typeToScala)
  }

  private def typeToScala(tpe: TypeTree): TypeRef =
    typeToScala(tpe, false)

  private def typeToScala(tpe: TypeTree, anyAsDynamic: Boolean): TypeRef = {
    tpe match {
      case TypeRefTree(tpe: CoreType, Nil) =>
        coreTypeToScala(tpe, anyAsDynamic)

      case TypeRefTree(TypeName("ReadonlyArray"), List(arrayType)) =>
        TypeRef(QualifiedName.JSArray, List(Wildcard(Some(typeToScala(arrayType)))))

      case TypeRefTree(base, targs) =>
        val baseTypeRef = base match {
          case TypeName("Array") => QualifiedName.Array
          case TypeName("Error") => QualifiedName.JSError
          case TypeName("Date") => QualifiedName.JSDate
          case TypeName("Function") => QualifiedName.FunctionBase
          case TypeName("object") => QualifiedName.Object
          case TypeName("RegExp") => QualifiedName.RegExp
          case TypeName("PromiseLike") => QualifiedName.Thenable
          case TypeName("Float32Array") => QualifiedName.Float32Array
          case TypeName("Float64Array") => QualifiedName.Float64Array
          case TypeName("Int8Array") => QualifiedName.Int8Array
          case TypeName("Int16Array") => QualifiedName.Int16Array
          case TypeName("Int32Array") => QualifiedName.Int32Array
          case TypeName("Uint8Array") => QualifiedName.Uint8Array
          case TypeName("Uint16Array") => QualifiedName.Uint16Array
          case TypeName("Uint32Array") => QualifiedName.Uint32Array
          case TypeName("Uint8ClampedArray") => QualifiedName.Uint8ClampedArray
          case TypeName("ArrayBuffer") => QualifiedName.ArrayBuffer
          case TypeName("ArrayBufferView") => QualifiedName.ArrayBufferView
          case TypeName("DataView") => QualifiedName.DataView
          case TypeNameName(name) => QualifiedName(name)
          case QualifiedTypeName(qualifier, TypeNameName(name)) =>
            val qual1 = qualifier map (x => Name(x.name))
            QualifiedName((qual1 :+ name): _*)
          case _: CoreType => throw new MatchError(base)
        }
        TypeRef(baseTypeRef, targs map typeToScala)

      case ConstantType(StringLiteral(_)) =>
        TypeRef.String

      case ConstantType(IntLiteral(i)) =>
        TypeRef.Int

      case ConstantType(DoubleLiteral(d)) =>
        TypeRef.Double

      case ConstantType(BooleanLiteral(_)) =>
        TypeRef.Boolean

      case ObjectType(List(IndexMember(_, TypeRefTree(CoreType("string"), _), valueType, _))) =>
        val valueTpe = typeToScala(valueType)
        TypeRef(QualifiedName.Dictionary, List(valueTpe))

      case ObjectType(members) =>
        TypeRef.Any

      case FunctionType(FunSignature(tparams, params, Some(resultType))) =>
        if (!tparams.isEmpty) {
          // Type parameters in function types are not supported
          TypeRef.Function
        } else if (params.exists(_.tpe.exists(_.isInstanceOf[RepeatedType]))) {
          // Repeated params in function types are not supported
          TypeRef.Function
        } else {
          val paramTypes =
            for (FunParam(_, _, TypeOrAny(tpe)) <- params)
              yield typeToScala(tpe)
          val resType = resultType match {
            case TypeRefTree(CoreType("any"), Nil) => TypeRef.ScalaAny
            case _ => typeToScala(resultType)
          }
          val targs = paramTypes :+ resType

          val typeRef = if (params.nonEmpty && params.head.name.name == "this") {
            QualifiedName.ThisFunction(params.size - 1)
          } else {
            QualifiedName.Function(params.size)
          }
          TypeRef(typeRef, targs)
        }

      case IntersectionType(left, right) =>
        def visit(tpe: TypeTree, visited: List[TypeRef]): List[TypeRef] = {
          tpe match {
            case IntersectionType(left, right) =>
              visit(left, visit(right, visited))
            case _ =>
              typeToScala(tpe) :: visited
          }
        }
        TypeRef.Intersection(visit(tpe, Nil).distinct)

      case UnionType(left, right) =>
        def visit(tpe: TypeTree, visited: List[TypeRef]): List[TypeRef] = {
          tpe match {
            case UnionType(left, right) =>
              visit(left, visit(right, visited))
            case _ =>
              typeToScala(tpe) :: visited
          }
        }

        TypeRef.Union(visit(tpe, Nil).distinct)

      case TypeQuery(expr) =>
        TypeRef.Singleton(QualifiedName((expr.qualifier :+ expr.name).map(
            ident => Name(ident.name)): _*))

      case TupleType(targs) =>
          TypeRef(QualifiedName.Tuple(targs.length), targs map typeToScala)

      case RepeatedType(underlying) =>
        TypeRef(Name.REPEATED, List(typeToScala(underlying)))

      case IndexedQueryType(_) =>
        TypeRef.String

      case TypeGuard =>
        TypeRef.Boolean
        
      case PolymorphicThisType =>
        TypeRef.This

      case _ =>
        // ???
        TypeRef.Any
    }
  }

  private def coreTypeToScala(tpe: CoreType,
      anyAsDynamic: Boolean = false): TypeRef = {

    tpe.name match {
      case "any"       => if (anyAsDynamic) TypeRef.Dynamic else TypeRef.Any
      case "dynamic"   => TypeRef.Dynamic
      case "void"      => TypeRef.Unit
      case "number"    => TypeRef.Double
      case "bool"      => TypeRef.Boolean
      case "boolean"   => TypeRef.Boolean
      case "string"    => TypeRef.String
      case "null"      => TypeRef.Null
      case "undefined" => TypeRef.Unit
      case "never"     => TypeRef.Nothing
    }
  }
}

object Importer {
  private val AnyType = TypeRefTree(CoreType("any"))
  private val DynamicType = TypeRefTree(CoreType("dynamic"))

  private implicit class OptType(val optType: Option[TypeTree]) extends AnyVal {
    @inline def orAny: TypeTree = optType.getOrElse(AnyType)
    @inline def orDynamic: TypeTree = optType.getOrElse(DynamicType)
  }

  private object TypeOrAny {
    @inline def unapply(optType: Option[TypeTree]) = Some(optType.orAny)
  }

  private object IdentName {
    @inline def unapply(ident: Ident) =
      Some(Name(ident.name))
  }

  private object TypeNameName {
    @inline def apply(typeName: Name) =
      TypeName(typeName.name)
    @inline def unapply(typeName: TypeName) =
      Some(Name(typeName.name))
  }

  private object PropertyNameName {
    @inline def unapply(propName: PropertyName) =
      Some(Name(propName.name))
  }
}
