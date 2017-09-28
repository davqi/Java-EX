package xdean.jex.util.reflect;

import static xdean.jex.util.function.Predicates.not;
import static xdean.jex.util.lang.PrimitiveTypeUtil.toWrapper;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FunctionInterfaceUtil {

  /**
   * Get the method from a function interface
   *
   * @param clz
   * @return null if the given class is not a function interface
   */
  public static <T> Method getFunctionInterfaceMethod(Class<?> clz) {
    if (!clz.isInterface()) {
      return null;
    }
    Method[] ms = Stream.of(clz.getMethods())
        .filter(m -> !(m.isDefault() || Modifier.isStatic(m.getModifiers()) || Modifier.isPrivate(m.getModifiers())))
        .toArray(Method[]::new);
    if (ms.length != 1) {
      return null;
    }
    return ms[0];
  }

  /**
   * Adapt a method to a function interface.<br>
   * For example:
   *
   * <pre>
   * <code>
   * static int increment(int i){
   *  return i+1;
   * }
   * Method m = ...
   * UnaryOperator<Integer> uo = methodToFunctionInterface(m, null, UnaryOperator.class);//work
   * UnaryOperator<Integer> uo = methodToFunctionInterface(m, null, UnaryOperator.class, Integer.class);//work and more safe
   * UnaryOperator<Integer> uo = methodToFunctionInterface(m, null, UnaryOperator.class, String.class);//return null
   * </code>
   * </pre>
   *
   * @param method The method to adapt. Ensure the method can be access.
   * @param target The method's target. If the method is static, target should be null.
   * @param functionInterfaceClass The function interface to adapt to.
   * @param conflictGenericTypes If the function interface has generic type, you can specify them in order. If a
   *          conflict type is null, it will be ignored.
   * @return Instance of the function interface. Or null if can't adapt to. Note that returned object is raw type. If
   *         you don't specify conflict generic types, IllegalArgumentException(type mismatch) may happen when you call
   *         it.
   */
  @SuppressWarnings("unchecked")
  public static <T> T methodToFunctionInterface(Method method, Object target, Class<T> functionInterfaceClass,
      Class<?>... conflictGenericTypes) {
    Method functionMethod = getFunctionInterfaceMethod(functionInterfaceClass);
    if (functionMethod == null) {
      return null;
    }
    if (functionMethod.getParameterCount() != method.getParameterCount()) {
      return null;
    }
    // Map the conflict type
    Map<TypeVariable<?>, Class<?>> conflictTypeMap = new HashMap<>();
    TypeVariable<Class<T>>[] typeParameters = functionInterfaceClass.getTypeParameters();
    if (conflictGenericTypes.length > typeParameters.length) {
      throw new IllegalArgumentException("The conflict generic types are too many. Expect " + typeParameters.length);
    }
    for (int i = 0; i < conflictGenericTypes.length; i++) {
      conflictTypeMap.put(typeParameters[i], toWrapper(conflictGenericTypes[i]));
    }
    // Map the generic reference
    Map<TypeVariable<?>, Type> typeVariableReference = getTypeVariableReference(functionInterfaceClass);
    Function<TypeVariable<?>, Class<?>> getActualTypeVariable = tv -> {
      Type next;
      while (true) {
        next = typeVariableReference.get(tv);
        if (next == null) {
          return conflictTypeMap.get(tv);
        }
        if (next instanceof Class<?>) {
          return (Class<?>) next;
        }
        tv = (TypeVariable<?>) next;
      }
    };
    // Resolve return type
    Class<?> returnType = toWrapper(method.getReturnType());
    Type functionGenericReturnType = functionMethod.getGenericReturnType();
    if (returnType == void.class && functionGenericReturnType == void.class) {
    } else if (functionGenericReturnType instanceof Class) {
      if (!toWrapper((Class<?>) functionGenericReturnType).isAssignableFrom(returnType)) {
        return null;
      }
    } else if (functionGenericReturnType instanceof TypeVariable) {
      TypeVariable<?> tv = (TypeVariable<?>) functionGenericReturnType;
      Class<?> conflictType = getActualTypeVariable.apply(tv);
      if (conflictType != null) {
        if (!conflictType.equals(returnType)) {
          return null;
        }
      } else if (FunctionInterfaceUtil.matchTypeBounds(returnType, tv)) {
        conflictTypeMap.put(tv, returnType);
      } else {
        return null;
      }
    }
    // Resolve parameters
    Type[] functionParams = functionMethod.getGenericParameterTypes();
    Class<?>[] params = method.getParameterTypes();
    for (int i = 0; i < params.length; i++) {
      Type functionParamType = functionParams[i];
      Class<?> paramType = toWrapper(params[i]);
      if (functionParamType instanceof Class) {
        if (!paramType.isAssignableFrom(
            toWrapper((Class<?>) functionParamType))) {
          return null;
        }
      } else if (functionParamType instanceof TypeVariable) {
        TypeVariable<?> tv = (TypeVariable<?>) functionParamType;
        Class<?> conflictType = getActualTypeVariable.apply(tv);
        if (conflictType != null) {
          if (!conflictType.equals(paramType)) {
            return null;
          }
        } else if (FunctionInterfaceUtil.matchTypeBounds(paramType, tv)) {
          conflictTypeMap.put(tv, paramType);
        } else {
          return null;
        }
      } else {
        log.warn("Can't handle GenericParameterType: {} with type {}", paramType, paramType.getClass());
        return null;
      }
    }
    // Resolve throws
    List<Type> functionExceptionTypes = Arrays.asList(functionMethod.getGenericExceptionTypes());
    for (Class<?> exceptionType : method.getExceptionTypes()) {
      if (Exception.class.isAssignableFrom(exceptionType)
          && !RuntimeException.class.isAssignableFrom(exceptionType)
          && !functionExceptionTypes.stream().anyMatch(
              functionThrowType -> {
                Class<?> functionThrowClass = null;
                if (functionThrowType instanceof Class) {
                  functionThrowClass = (Class<?>) functionThrowType;
                } else if (functionThrowType instanceof TypeVariable) {
                  Class<?> conflictType = conflictTypeMap.get(functionThrowType);
                  if (conflictType == null) {
                    return FunctionInterfaceUtil.matchTypeBounds(exceptionType, (TypeVariable<?>) functionThrowType);
                  } else {
                    functionThrowClass = conflictType;
                  }
                } else {
                  log.warn("Can't handle GenericException: {} with type {}", functionThrowType,
                      functionThrowType.getClass());
                  return false;
                }
                return functionThrowClass.isAssignableFrom(exceptionType);
              })) {
        return null;
      }
    }
    return (T) Proxy.newProxyInstance(functionInterfaceClass.getClassLoader(),
        new Class[] { functionInterfaceClass }, (obj, m, args) -> {
          if (m.equals(functionMethod)) {
            return method.invoke(target, args);
          }
          return m.invoke(obj, args);
        });
  }

  private static boolean matchTypeBounds(Class<?> clz, TypeVariable<?> tv) {
    Class<?> wrapClz = toWrapper(clz);
    return FunctionInterfaceUtil.getAllBounds(tv).allMatch(
        c -> toWrapper(c).isAssignableFrom(wrapClz));
  }

  private static Stream<Class<?>> getAllBounds(TypeVariable<?> tv) {
    return Stream.of(tv.getBounds())
        .flatMap(t -> {
          if (t instanceof Class) {
            return Stream.of((Class<?>) t);
          } else if (t instanceof TypeVariable) {
            return getAllBounds(((TypeVariable<?>) t));
          } else {
            log.warn("Can't handle TypeVariable Bound: {} with type {}", t, t.getClass());
            return Stream.empty();
          }
        });
  }

  private static Map<TypeVariable<?>, Type> getTypeVariableReference(Class<?> clz) {
    HashMap<TypeVariable<?>, Type> map = new HashMap<>();
    if (clz.getSuperclass() != null) {
      map.putAll(getTypeVariableReference(clz.getSuperclass()));
    }
    Arrays.asList(clz.getInterfaces()).forEach(c -> map.putAll(getTypeVariableReference(c)));
    Stream.concat(Stream.of(clz.getGenericSuperclass()), Stream.of(clz.getGenericInterfaces()))
        .filter(not(null))
        .forEach(c -> {
          if (c instanceof Class) {
          } else if (c instanceof ParameterizedType) {
            Type[] actualTypeArguments = ((ParameterizedType) c).getActualTypeArguments();
            TypeVariable<?>[] implTypeParams = ((Class<?>) ((ParameterizedType) c).getRawType())
                .getTypeParameters();
            for (int i = 0; i < actualTypeArguments.length; i++) {
              map.put(implTypeParams[i], actualTypeArguments[i]);
            }
          } else {
            log.warn("Unknown Generic Type: {} with type {}", c, c.getClass());
          }
        });
    return map;
  }
}