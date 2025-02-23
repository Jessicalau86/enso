/**
 * Generates TypeScript bindings from a schema describing types and their serialization.
 *
 * Internally, the generated types deserialize their data on demand. This benefits performance: If we eagerly
 * deserialized a serialized tree to a tree of objects in memory, creating the tree would produce many heap-allocated
 * objects, and visiting the tree would require dereferencing chains of heap pointers. Deserializing while traversing
 * allows the optimizer to stack-allocate the temporary objects, saving time and reducing GC pressure.
 */

import ts from 'typescript'
import type * as Schema from './schema.js'
import {
  Type,
  abstractTypeDeserializer,
  abstractTypeVariants,
  fieldDeserializer,
  fieldVisitor,
  seekViewDyn,
  support,
  supportImports,
} from './serialization.js'
import {
  assignmentStatement,
  forwardToSuper,
  mapIdent,
  modifiers,
  namespacedName,
  toCamel,
  toPascal,
} from './util.js'
const tsf: ts.NodeFactory = ts.factory

const addressIdent = tsf.createIdentifier('address')
const viewIdent = tsf.createIdentifier('view')

// === Public API ===

export function implement(schema: Schema.Schema): string {
  const file = ts.createSourceFile('source.ts', '', ts.ScriptTarget.ESNext, false, ts.ScriptKind.TS)
  const printer = ts.createPrinter({
    newLine: ts.NewLineKind.LineFeed,
    omitTrailingSemicolon: true,
  })
  let output = '// *** THIS FILE GENERATED BY `parser-codegen` ***\n'

  function emit(data: ts.Node) {
    output += printer.printNode(ts.EmitHint.Unspecified, data, file)
    output += '\n'
  }

  emit(
    tsf.createImportDeclaration(
      [],
      tsf.createImportClause(
        false,
        undefined,
        tsf.createNamedImports(
          Array.from(Object.entries(supportImports), ([name, isTypeOnly]) =>
            tsf.createImportSpecifier(isTypeOnly, undefined, tsf.createIdentifier(name)),
          ),
        ),
      ),
      tsf.createStringLiteral('../parserSupport', true),
      undefined,
    ),
  )
  for (const id in schema.types) {
    const ty = schema.types[id]
    if (ty?.parent == null) {
      const discriminants = schema.serialization[id]?.discriminants
      if (discriminants == null) {
        emit(makeConcreteType(id, schema))
      } else {
        const ty = makeAbstractType(id, discriminants, schema)
        emit(ty.module)
        emit(ty.export)
      }
    } else {
      // Ignore child types; they are generated when `makeAbstractType` processes the parent.
    }
  }
  return output
}

// === Implementation ===

function makeType(ref: Schema.TypeRef, schema: Schema.Schema): Type {
  const c = ref.class
  switch (c) {
    case 'type': {
      const ty = schema.types[ref.id]
      if (!ty) throw new Error(`Invalid type ref: ${ref.id}`)
      const parent = ty.parent != null ? schema.types[ty.parent] : undefined
      const typeName = namespacedName(ty.name, parent?.name)
      const layout = schema.serialization[ref.id]
      if (!layout) throw new Error(`Invalid serialization ref: ${ref.id}`)
      if (layout.discriminants != null) {
        return Type.Abstract(typeName)
      } else {
        return Type.Concrete(typeName, layout.size)
      }
    }
    case 'primitive': {
      const p = ref.type
      switch (p) {
        case 'bool':
          return Type.Boolean
        case 'u32':
          return Type.UInt32
        case 'i32':
          return Type.Int32
        case 'u64':
          return Type.UInt64
        case 'i64':
          return Type.Int64
        case 'char':
          return Type.Char
        case 'string':
          return Type.String
        default: {
          const _ = p satisfies never
          throw new Error(`unreachable: PrimitiveType.type='${p}'`)
        }
      }
    }
    case 'sequence':
      return Type.Sequence(makeType(ref.type, schema))
    case 'option':
      return Type.Option(makeType(ref.type, schema))
    case 'result':
      return Type.Result(makeType(ref.type0, schema), makeType(ref.type1, schema))
    default: {
      const _ = c satisfies never
      throw new Error(`unreachable: TypeRef.class='${c}' in ${JSON.stringify(ref)}`)
    }
  }
}

type Field = {
  name: string
  type: Type
  offset: number
}

function makeField(
  name: string,
  typeRef: Schema.TypeRef,
  offset: number,
  schema: Schema.Schema,
): Field {
  return {
    name: mapIdent(toCamel(name)),
    type: makeType(typeRef, schema),
    offset: offset,
  }
}

function makeGetter(field: Field): ts.GetAccessorDeclaration {
  return fieldDeserializer(tsf.createIdentifier(field.name), field.type, field.offset)
}

function makeConcreteType(id: string, schema: Schema.Schema): ts.ClassDeclaration {
  const ident = tsf.createIdentifier(toPascal(schema.types[id]!.name))
  return makeClass(
    [modifiers.export],
    ident,
    [
      forwardToSuper(viewIdent, support.DataView),
      makeReadMethod(
        ident,
        addressIdent,
        viewIdent,
        tsf.createNewExpression(ident, [], [seekViewDyn(viewIdent, addressIdent)]),
      ),
    ],
    id,
    schema,
  )
}

function makeReadMethod(
  typeIdent: ts.Identifier,
  addressIdent: ts.Identifier,
  viewIdent: ts.Identifier,
  returnValue: ts.Expression,
): ts.MethodDeclaration {
  const offsetParam = tsf.createParameterDeclaration(
    [],
    undefined,
    addressIdent,
    undefined,
    tsf.createTypeReferenceNode('number'),
    undefined,
  )
  const cursorParam = tsf.createParameterDeclaration(
    [],
    undefined,
    viewIdent,
    undefined,
    support.DataView,
    undefined,
  )
  return tsf.createMethodDeclaration(
    [modifiers.static],
    undefined,
    'read',
    undefined,
    [],
    [cursorParam, offsetParam],
    tsf.createTypeReferenceNode(typeIdent),
    tsf.createBlock([tsf.createReturnStatement(returnValue)]),
  )
}

function makeReadFunction(
  typeIdent: ts.Identifier,
  addressIdent: ts.Identifier,
  viewIdent: ts.Identifier,
  returnValue: ts.Expression,
): ts.FunctionDeclaration {
  const offsetParam = tsf.createParameterDeclaration(
    [],
    undefined,
    addressIdent,
    undefined,
    tsf.createTypeReferenceNode('number'),
    undefined,
  )
  const cursorParam = tsf.createParameterDeclaration(
    [],
    undefined,
    viewIdent,
    undefined,
    support.DataView,
    undefined,
  )
  return tsf.createFunctionDeclaration(
    [modifiers.export],
    undefined,
    'read',
    [],
    [cursorParam, offsetParam],
    tsf.createTypeReferenceNode(typeIdent),
    tsf.createBlock([tsf.createReturnStatement(returnValue)]),
  )
}

function makeVisitFunction(fields: Field[]): ts.MethodDeclaration {
  const ident = tsf.createIdentifier('visitChildren')
  const visitorParam = tsf.createIdentifier('visitor')
  const visitorParamDecl = tsf.createParameterDeclaration(
    undefined,
    undefined,
    visitorParam,
    undefined,
    support.ObjectVisitor,
  )
  const visitSuperChildren = tsf.createCallExpression(
    tsf.createPropertyAccessExpression(tsf.createSuper(), ident),
    undefined,
    [visitorParam],
  )
  const fieldVisitations: ts.Expression[] = []
  for (const field of fields) {
    if (field.type.visitor === 'visitValue') {
      fieldVisitations.push(
        tsf.createCallExpression(visitorParam, undefined, [
          tsf.createPropertyAccessExpression(tsf.createThis(), field.name),
        ]),
      )
    } else if (field.type.visitor != null) {
      fieldVisitations.push(
        tsf.createCallExpression(
          tsf.createPropertyAccessExpression(tsf.createThis(), toCamel('visit_' + field.name)),
          undefined,
          [visitorParam],
        ),
      )
    }
  }
  const toBool = (value: ts.Expression) =>
    tsf.createPrefixUnaryExpression(
      ts.SyntaxKind.ExclamationToken,
      tsf.createPrefixUnaryExpression(ts.SyntaxKind.ExclamationToken, value),
    )
  const expression = fieldVisitations.reduce(
    (lhs, rhs) => tsf.createBinaryExpression(lhs, ts.SyntaxKind.BarBarToken, toBool(rhs)),
    visitSuperChildren,
  )
  return tsf.createMethodDeclaration(
    [tsf.createModifier(ts.SyntaxKind.OverrideKeyword)],
    undefined,
    ident,
    undefined,
    undefined,
    [visitorParamDecl],
    tsf.createTypeReferenceNode('boolean'),
    tsf.createBlock([tsf.createReturnStatement(expression)]),
  )
}

function makeGetters(id: string, schema: Schema.Schema): ts.ClassElement[] {
  const serialization = schema.serialization[id]
  const type = schema.types[id]
  if (serialization == null || type == null) throw new Error(`Invalid type id: ${id}`)
  const fields = serialization.fields.map(([name, offset]: [string, number]) => {
    const field = type.fields[name]
    if (field == null) throw new Error(`Invalid field name '${name}' for type '${type.name}'`)
    return makeField(name, field, offset, schema)
  })
  return [
    ...fields.map(makeGetter),
    ...fields.map(makeElementVisitor).filter((v): v is ts.ClassElement => v != null),
    makeVisitFunction(fields),
  ]
}

function makeElementVisitor(field: Field): ts.ClassElement | undefined {
  if (field.type.visitor == null) return undefined
  const ident = tsf.createIdentifier(toCamel('visit_' + field.name))
  return fieldVisitor(ident, field.type, field.offset)
}

function makeClass(
  modifiers: ts.Modifier[],
  name: ts.Identifier,
  members: ts.ClassElement[],
  id: string,
  schema: Schema.Schema,
): ts.ClassDeclaration {
  return tsf.createClassDeclaration(
    modifiers,
    name,
    undefined,
    [
      tsf.createHeritageClause(ts.SyntaxKind.ExtendsKeyword, [
        tsf.createExpressionWithTypeArguments(support.LazyObject, []),
      ]),
    ],
    [...members, ...makeGetters(id, schema)],
  )
}

type ChildType = {
  definition: ts.ClassDeclaration
  name: ts.Identifier
  enumMember: ts.EnumMember
}

function makeChildType(
  base: ts.Identifier,
  id: string,
  discriminant: string,
  schema: Schema.Schema,
): ChildType {
  const ty = schema.types[id]
  if (ty == null) throw new Error(`Invalid type id: ${id}`)
  const name = toPascal(ty.name)
  const ident = tsf.createIdentifier(name)
  const typeIdent = tsf.createIdentifier('Type')
  const addressIdent = tsf.createIdentifier('address')
  const viewIdent = tsf.createIdentifier('view')
  const discriminantInt = tsf.createNumericLiteral(parseInt(discriminant, 10))
  return {
    definition: tsf.createClassDeclaration(
      [modifiers.export],
      name,
      undefined,
      [
        tsf.createHeritageClause(ts.SyntaxKind.ExtendsKeyword, [
          tsf.createExpressionWithTypeArguments(base, []),
        ]),
      ],
      [
        tsf.createPropertyDeclaration(
          [modifiers.readonly],
          'type',
          undefined,
          tsf.createTypeReferenceNode(tsf.createQualifiedName(typeIdent, name)),
          undefined,
        ),
        tsf.createConstructorDeclaration(
          [],
          [
            tsf.createParameterDeclaration(
              [],
              undefined,
              viewIdent,
              undefined,
              support.DataView,
              undefined,
            ),
          ],
          tsf.createBlock([
            tsf.createExpressionStatement(
              tsf.createCallExpression(tsf.createSuper(), [], [viewIdent]),
            ),
            assignmentStatement(
              tsf.createPropertyAccessExpression(tsf.createThis(), 'type'),
              tsf.createPropertyAccessExpression(typeIdent, name),
            ),
          ]),
        ),
        makeReadMethod(
          ident,
          addressIdent,
          viewIdent,
          tsf.createNewExpression(ident, [], [seekViewDyn(viewIdent, addressIdent)]),
        ),
        ...makeGetters(id, schema),
      ],
    ),
    name: tsf.createIdentifier(name),
    enumMember: tsf.createEnumMember(name, discriminantInt),
  }
}

type AbstractType = {
  module: ts.ModuleDeclaration
  export: ts.TypeAliasDeclaration
}

function makeAbstractType(
  id: string,
  discriminants: Schema.DiscriminantMap,
  schema: Schema.Schema,
): AbstractType {
  const ty = schema.types[id]!
  const name = toPascal(ty.name)
  const ident = tsf.createIdentifier(name)
  const type = tsf.createTypeReferenceNode(ident)
  const baseIdent = tsf.createIdentifier('AbstractBase')
  const childTypes = Array.from(Object.entries(discriminants), ([discrim, id]: [string, string]) =>
    makeChildType(baseIdent, id, discrim, schema),
  )

  const moduleDecl = tsf.createModuleDeclaration(
    [modifiers.export],
    ident,
    tsf.createModuleBlock([
      makeClass(
        [modifiers.export, modifiers.abstract],
        baseIdent,
        [forwardToSuper(viewIdent, support.DataView, [modifiers.protected])],
        id,
        schema,
      ),
      tsf.createEnumDeclaration(
        [modifiers.export, modifiers.const],
        'Type',
        childTypes.map(child => child.enumMember),
      ),
      makeExportConstVariable(
        'typeNames',
        tsf.createArrayLiteralExpression(
          childTypes.map(child => tsf.createStringLiteralFromNode(child.name)),
        ),
      ),
      ...childTypes.map(child => child.definition),
      tsf.createTypeAliasDeclaration(
        [modifiers.export],
        ident,
        undefined,
        tsf.createUnionTypeNode(childTypes.map(child => tsf.createTypeReferenceNode(child.name))),
      ),
      abstractTypeVariants(childTypes.map(child => child.name)),
      makeReadFunction(
        ident,
        addressIdent,
        viewIdent,
        abstractTypeDeserializer(ident, viewIdent, addressIdent),
      ),
      makeIsInstance(type, baseIdent),
    ]),
  )
  const abstractTypeExport = tsf.createTypeAliasDeclaration(
    [modifiers.export],
    ident,
    undefined,
    tsf.createTypeReferenceNode(tsf.createQualifiedName(ident, ident)),
  )
  return { module: moduleDecl, export: abstractTypeExport }
}

function makeExportConstVariable(
  varName: string,
  initializer: ts.Expression,
): ts.VariableStatement {
  return tsf.createVariableStatement(
    [modifiers.export],
    tsf.createVariableDeclarationList(
      [
        tsf.createVariableDeclaration(
          varName,
          undefined,
          undefined,
          tsf.createAsExpression(initializer, tsf.createTypeReferenceNode('const')),
        ),
      ],
      ts.NodeFlags.Const,
    ),
  )
}

function makeIsInstance(type: ts.TypeNode, baseIdent: ts.Identifier): ts.FunctionDeclaration {
  const param = tsf.createIdentifier('obj')
  const paramDecl = tsf.createParameterDeclaration(
    undefined,
    undefined,
    param,
    undefined,
    tsf.createTypeReferenceNode('unknown'),
  )
  const returnValue = tsf.createBinaryExpression(param, ts.SyntaxKind.InstanceOfKeyword, baseIdent)
  return tsf.createFunctionDeclaration(
    [modifiers.export],
    undefined,
    'isInstance',
    undefined,
    [paramDecl],
    tsf.createTypePredicateNode(undefined, param, type),
    tsf.createBlock([tsf.createReturnStatement(returnValue)]),
  )
}
