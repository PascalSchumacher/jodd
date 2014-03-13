// Copyright (c) 2003-2014, Jodd Team (jodd.org). All Rights Reserved.

package jodd.madvoc.component;

import jodd.introspector.FieldDescriptor;
import jodd.introspector.MethodDescriptor;
import jodd.madvoc.ActionConfig;
import jodd.madvoc.ScopeData;
import jodd.madvoc.ScopeType;
import jodd.madvoc.MadvocException;
import jodd.madvoc.meta.In;
import jodd.madvoc.meta.InOut;
import jodd.madvoc.meta.Out;
import jodd.util.ArraysUtil;
import jodd.util.ReflectUtil;
import jodd.introspector.ClassDescriptor;
import jodd.introspector.ClassIntrospector;
import jodd.util.StringUtil;

import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.lang.reflect.Method;
import java.lang.reflect.Field;

/**
 * Collection of {@link jodd.madvoc.ScopeData scope data} of certain type.
 * For each action class and action method it holds an array of ScopeData objects.
 * Each element of that array represents data for one ScopeType.
 * Some elements might be <code>null</code> as well.
 */
public class ScopeDataResolver {

	private static final ScopeData[] EMPTY_SCOPEDATA = new ScopeData[0];

	protected Map<Object, ScopeData[]> scopeMap = new HashMap<Object, ScopeData[]>();

	// ---------------------------------------------------------------- bean

	public ScopeData.In[] lookupInData(Class type, ScopeType scopeType) {
		ScopeData scopeData = lookup(type, null, scopeType);

		if (scopeData == null) {
			return null;
		}

		return scopeData.in;
	}

	public ScopeData.Out[] lookupOutData(Class type, ScopeType scopeType) {
		ScopeData scopeData = lookup(type, null, scopeType);

		if (scopeData == null) {
			return null;
		}

		return scopeData.out;
	}

	// ---------------------------------------------------------------- action config

	/**
	 * Lookups INput data for given action config. Returns <code>null</code>
	 * if scope data is not found.
	 */
	public ScopeData.In[] lookupInData(ActionConfig actionConfig, ScopeType scopeType) {
		Method method = actionConfig.getActionClassMethod();
		if (actionConfig.hasActionMethodArguments() == false) {
			method = null;
		}
		ScopeData scopeData = lookup(actionConfig.getActionClass(), method, scopeType);

		if (scopeData == null) {
			return null;
		}

		return scopeData.in;
	}

	/**
	 * Lookups OUTput data for given object and scope type.
	 * Returns <code>null</code> if no data is found.
	 */
	public ScopeData.Out[] lookupOutData(ActionConfig actionConfig, ScopeType scopeType) {
		Method method = actionConfig.getActionClassMethod();
		if (actionConfig.hasActionMethodArguments() == false) {
			method = null;
		}
		ScopeData scopeData = lookup(actionConfig.getActionClass(), method, scopeType);

		if (scopeData == null) {
			return null;
		}

		return scopeData.out;
	}

	// ---------------------------------------------------------------- main lookup

	/**
	 * Lookups cashed scope data. If scope data doesn't exist,
	 * <code>null</code> is returned.
	 */
	protected ScopeData lookup(Class type, Method method, ScopeType scopeType) {
		ScopeData[] scopeDataForClass = scopeMap.get(type);

		if (scopeDataForClass == null) {
			scopeDataForClass = inspectScopeData(type);

			scopeMap.put(type, scopeDataForClass);
		}

		ScopeData[] scopeDataForMethod = EMPTY_SCOPEDATA;

		if (method != null) {
			scopeDataForMethod = scopeMap.get(method);

			if (scopeDataForMethod == null) {
				scopeDataForMethod = inspectScopeData(method);

				scopeMap.put(method, scopeDataForMethod);
			}
		}

		// scope data

		ScopeData[] scopeData;

		if (scopeDataForClass.length == 0) {
			if (scopeDataForMethod.length == 0) {
				// nothing found
				return null;
			}
			// only method data
			scopeData = scopeDataForMethod;
		} else {
			if (scopeDataForMethod.length == 0) {
				// only class data
				scopeData = scopeDataForClass;
			} else {
				// both data exist, join
				scopeData = ArraysUtil.join(scopeDataForClass, scopeDataForMethod);
			}
		}

		// scope type

		ScopeData sd = scopeData[scopeType.value()];

		if (sd == null) {
			return null;
		}

		return sd;
	}

	// ---------------------------------------------------------------- common

	/**
	 * Inspects and returns scope data for all available scopes.
	 */
	protected ScopeData[] inspectScopeData(Object key) {
		ScopeType[] allScopeTypes = ScopeType.values();

		ScopeData[] scopeData = new ScopeData[allScopeTypes.length];

		int count = 0;
		if (key instanceof Class) {
			for (ScopeType scopeType : allScopeTypes) {
				ScopeData sd = inspectScopeData((Class) key, scopeType);
				if (sd != null) {
					count++;
				}
				scopeData[scopeType.value()] = sd;
			}
		} else if (key instanceof Method) {
			for (ScopeType scopeType : allScopeTypes) {
				ScopeData sd = inspectMethodScopeData((Method) key, null, scopeType);
				if (sd != null) {
					count++;
				}
				scopeData[scopeType.value()] = sd;
			}
		} else {
			throw new MadvocException("Invalid type: " + key);
		}
		if (count == 0) {
			scopeData = EMPTY_SCOPEDATA;
		}

		return scopeData;
	}


	// ---------------------------------------------------------------- method data

	/**
	 * Inspects all method parameters for scope data.
	 */
	protected ScopeData inspectMethodScopeData(
			Method method,
			String[] methodParameterNames,
			ScopeType scopeType) {

		Annotation[][] annotations = method.getParameterAnnotations();
		Class<?>[] types = method.getParameterTypes();

		int paramsCount = types.length;

		ScopeData sd = new ScopeData();
		sd.in = new ScopeData.In[paramsCount];
		sd.out = new ScopeData.Out[paramsCount];

		int incount = 0, outcount = 0;

		for (int i = 0; i < paramsCount; i++) {
			Annotation[] paramAnnotations = annotations[i];

			Class type = types[i];
			String name = methodParameterNames[i];

			boolean hasAnnotation = false;
			for (Annotation annotation : paramAnnotations) {

				if (annotation instanceof In) {
					sd.in[i] = inspectIn((In) annotation, scopeType, name, type);
					if (sd.in[i] != null) {
						incount++;
					}
					hasAnnotation = true;
				} else if (annotation instanceof Out) {
					sd.out[i] = inspectOut((Out) annotation, scopeType, StringUtil.uncapitalize(type.getSimpleName()), type);
					outcount++;
				}
			}

			// annotations not available!
			if ((hasAnnotation == false)) {
				throw new MadvocException("Unamrked parameter in: " + method);
			}
		}
		if (incount == 0) {
			sd.in = null;
		}
		if (outcount == 0) {
			sd.out = null;
		}
		return sd;
	}



	// ---------------------------------------------------------------- inspect

	/**
	 * Fills value and property name.
	 */
	protected void fillNameTarget(ScopeData.In ii, String value, String propertyName) {
		value = value.trim();
		if (value.length() > 0) {
			ii.name = value;
			ii.target = propertyName;
		} else {
			ii.name = propertyName;
			ii.target = null;
		}
	}

	/**
	 * Fills value and property name.
	 */
	protected void fillNameTarget(ScopeData.Out oi, String value, String propertyName) {
		value = value.trim();
		if (value.length() > 0) {
			oi.name = value;
			oi.target = propertyName;
		} else {
			oi.name = propertyName;
			oi.target = null;
		}
	}

	/**
	 * Inspects single IN annotation for a property.
	 */
	protected ScopeData.In inspectIn(In in, ScopeType scopeType, String propertyName, Class propertyType) {
		if (in == null) {
			return null;
		}
		ScopeType scope = in.scope();
		if (scope != scopeType) {
			return null;
		}
		ScopeData.In ii = new ScopeData.In();
		fillNameTarget(ii, in.value(), propertyName);
		ii.type = propertyType;
		ii.create = in.create();
		return ii;
	}

	/**
	 * Inspects single INOUT annotation as IN.
	 * @see #inspectIn(jodd.madvoc.meta.In, jodd.madvoc.ScopeType, String, Class)
	 */
	protected ScopeData.In inspectIn(InOut inOut, ScopeType scopeType, String propertyName, Class propertyType) {
		if (inOut == null) {
			return null;
		}
		ScopeType scope = inOut.scope();
		if (scope != scopeType) {
			return null;
		}
		ScopeData.In ii = new ScopeData.In();
		fillNameTarget(ii, inOut.value(), propertyName);
		ii.type = propertyType;
		ii.create = inOut.create();
		return ii;
	}

	/**
	 * Inspects single OUT annotation for a property.
	 */
	protected ScopeData.Out inspectOut(Out out, ScopeType scopeType, String propertyName, Class propertyType) {
		if (out == null) {
			return null;
		}
		ScopeType scope = out.scope();
		if (scope != scopeType) {
			return null;
		}
		ScopeData.Out oi = new ScopeData.Out();
		fillNameTarget(oi, out.value(), propertyName);
		oi.type = propertyType;
		return oi;
	}

	/**
	 * Inspects single INOUT annotation as OUT.
	 * @see #inspectOut(jodd.madvoc.meta.Out, jodd.madvoc.ScopeType, String, Class)
	 */
	protected ScopeData.Out inspectOut(InOut inOut, ScopeType scopeType, String propertyName, Class propertyType) {
		if (inOut == null) {
			return null;
		}
		ScopeType scope = inOut.scope();
		if (scope != scopeType) {
			return null;
		}
		ScopeData.Out oi = new ScopeData.Out();
		fillNameTarget(oi, inOut.value(), propertyName);
		oi.type = propertyType;
		return oi;
	}

	/**
	 * Inspect action for all In/Out annotations.
	 * Returns <code>null</code> if there are no In and Out data.
	 */
	protected ScopeData inspectScopeData(Class actionClass, ScopeType scopeType) {
		ClassDescriptor cd = ClassIntrospector.lookup(actionClass);
		FieldDescriptor[] fields = cd.getAllFieldDescriptors();
		MethodDescriptor[] methods = cd.getAllMethodDescriptors();

		List<ScopeData.In> listIn = new ArrayList<ScopeData.In>(fields.length + methods.length);
		List<ScopeData.Out> listOut = new ArrayList<ScopeData.Out>(fields.length + methods.length);


		// fields
		for (FieldDescriptor fieldDescriptor : fields) {
			Field field = fieldDescriptor.getField();

			Class fieldType = ReflectUtil.getRawType(field.getGenericType(), actionClass);

			In in = field.getAnnotation(In.class);
			ScopeData.In ii = inspectIn(in, scopeType, field.getName(), fieldType);
			if (ii != null) {
				listIn.add(ii);
			}
			InOut inout = field.getAnnotation(InOut.class);
			if (inout != null) {
				if (in != null) {
					throw new MadvocException("@InOut can not be used with @In: " + field.getDeclaringClass() + '#' + field.getName());
				}
				ii = inspectIn(inout, scopeType, field.getName(), field.getType());
				if (ii != null) {
					listIn.add(ii);
				}
			}

			Out out = field.getAnnotation(Out.class);
			ScopeData.Out oi = inspectOut(out, scopeType, field.getName(), fieldType);
			if (oi != null) {
				listOut.add(oi);
			}
			inout = field.getAnnotation(InOut.class);
			if (inout != null) {
				if (out != null) {
					throw new MadvocException("@InOut can not be used with @Out: " + field.getDeclaringClass() + '#' + field.getName());
				}
				oi = inspectOut(inout, scopeType, field.getName(), field.getType());
				if (oi != null) {
					listOut.add(oi);
				}
			}
		}

		// methods
		for (MethodDescriptor methodDescriptor : methods) {
			Method method = methodDescriptor.getMethod();

			String propertyName = ReflectUtil.getBeanPropertySetterName(method);
			if (propertyName != null) {
				In in = method.getAnnotation(In.class);
				ScopeData.In ii = inspectIn(in, scopeType, propertyName, method.getParameterTypes()[0]);
				if (ii != null) {
					listIn.add(ii);
				}
				InOut inout = method.getAnnotation(InOut.class);
				if (inout != null) {
					if (in != null) {
						throw new MadvocException("@InOut can not be used with @In: " + method.getDeclaringClass() + '#' + method.getName());
					}
					ii = inspectIn(inout, scopeType, propertyName, method.getParameterTypes()[0]);
					if (ii != null) {
						listIn.add(ii);
					}
				}
			}

			propertyName = ReflectUtil.getBeanPropertyGetterName(method);
			if (propertyName != null) {
				Out out = method.getAnnotation(Out.class);
				ScopeData.Out oi = inspectOut(out, scopeType, propertyName, method.getReturnType());
				if (oi != null) {
					listOut.add(oi);
				}
				InOut inout = method.getAnnotation(InOut.class);
				if (inout != null) {
					if (out != null) {
						throw new MadvocException("@InOut can not be used with @Out: " + method.getDeclaringClass() + '#' + method.getName());
					}
					oi = inspectOut(inout, scopeType, propertyName, method.getReturnType());
					if (oi != null) {
						listOut.add(oi);
					}
				}

			}
		}

		if ((listIn.isEmpty()) && (listOut.isEmpty())) {
			return null;
		}

		ScopeData scopeData = new ScopeData();
		if (listIn.isEmpty() == false) {
			scopeData.in = listIn.toArray(new ScopeData.In[listIn.size()]);
		}
		if (listOut.isEmpty() == false) {
			scopeData.out = listOut.toArray(new ScopeData.Out[listOut.size()]);
		}
		return scopeData;
	}

}