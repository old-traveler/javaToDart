package com.hyc;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataKeys;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.siyeh.ig.psiutils.ClassUtils;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JavaToDart extends AnAction {

    private Set<String> classMap = new HashSet();

    @Override
    public void actionPerformed(AnActionEvent e) {
        PsiFile file = e.getData(DataKeys.PSI_FILE);
        if (!checkFile(file)) {
            Messages.showErrorDialog("错误", "请选择java文件");
            return;
        }
        PsiJavaFile javaFile = (PsiJavaFile) file;
        List<DartClass> dartClassList = new ArrayList<>();
        if (javaFile.getClasses().length > 0) {
            for (PsiClass aClass : javaFile.getClasses()) {
                dartClassList.add(dealClass(aClass));
            }
        }

        StringBuilder stringBuilder = new StringBuilder();
        for (DartClass dartClass : dartClassList) {
            Stack<DartClass> stack = new Stack();
            String className = dartClass.genericity != null ? dartClass.genericity : dartClass.className;
            if (classMap.add(className)) {
                stack.push(dartClass);
            }
            while (!stack.isEmpty()) {
                DartClass item = stack.pop();
                List<DartClass> classList = toDart(item, stringBuilder);
                for (DartClass aClass : classList) {
                    String aClassName = aClass.genericity != null ? aClass.genericity : aClass.className;
                    if (classMap.add(aClassName)) {
                        stack.push(aClass);
                    }
                }
            }
        }
        String filePath = "/Users/heyucheng/Desktop/" + file.getName() + ".dart";
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(filePath);
            fos.write(stringBuilder.toString().getBytes());
            fos.close();
        } catch (FileNotFoundException ex) {
            ex.printStackTrace();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
        classMap.clear();
    }

    List<DartClass> toDart(DartClass dartClass, StringBuilder stringBuilder) {
        List<DartClass> dartClassList = new ArrayList<>();
        if (dartClass.dartFields.isEmpty()) return dartClassList;
        String className = dartClass.genericity != null ? dartClass.genericity : dartClass.className;
        stringBuilder.append("class " + className + " {\n");
        for (DartField dartField : dartClass.dartFields) {
            boolean needNewClass = dartField.typeClass != null && dartField.typeClass.dartFields != null;
            if (needNewClass) {
                dartClassList.add(dartField.typeClass);
                stringBuilder.append(dartField.typeClass.className + " " + dartField.name + ";\n");
            } else {
                String typeClassName = dartField.typeClass.className;
                if (typeClassName.equals("long")) {
                    typeClassName = "int";
                } else if (typeClassName.equals("float")) {
                    typeClassName = "double";
                } else if (typeClassName.equals("boolean")) {
                    typeClassName = "bool";
                }
                stringBuilder.append(typeClassName + " " + lineToHump(dartField.name) + ";\n");
            }
        }
        stringBuilder.append(className + ".fromJson(dynamic json) {\n");
        for (DartField dartField : dartClass.dartFields) {
            String humpName = lineToHump(dartField.name);
            stringBuilder.append("dynamic " + humpName + "Json = ");
            List<String> keys = dartField.keys;
            if (keys.isEmpty()) {
                keys.add(dartField.name);
            }
            for (int i = 0; i < keys.size(); i++) {
                if (i != 0) {
                    stringBuilder.append(" ?? ");
                }
                stringBuilder.append("json[\'" + keys.get(i) + "\']");
            }
            stringBuilder.append(";\n");
            stringBuilder.append("if (" + humpName + "Json != null)\n");
            if (dartField.typeClass.genericity != null && dartField.typeClass.dartFields != null) {
                stringBuilder.append(humpName + " = " + humpName + "Json.map((item)=>" + dartField.typeClass.genericity + ".fromJson(item)).toList();\n");
            } else if (dartField.typeClass.dartFields == null) {
                stringBuilder.append(humpName + " = " + humpName + "Json;\n");
            } else {
                stringBuilder.append(humpName + " = " + dartField.typeClass.className + ".fromJson(" + humpName + "Json);\n");
            }
        }
        stringBuilder.append("}\n");
        stringBuilder.append("}\n");
        return dartClassList;
    }


    DartClass dealClass(PsiClass psiClass) {
        DartClass dartClass = new DartClass(psiClass.getName());
        List<DartField> fields = new ArrayList<>();
        dartClass.dartFields = fields;
        for (PsiField field : psiClass.getAllFields()) {
            if (field.getModifierList() != null && field.getModifierList().hasExplicitModifier(PsiModifier.STATIC)) {
                continue;
            }
            DartField dartField = new DartField();
            dartField.name = field.getName();
            dartField.typeClass = parseDartClassByField(field);
            List<String> keys = new ArrayList<>();
            dartField.keys = keys;
            PsiAnnotation annotation = field.getAnnotation("com.google.gson.annotations.SerializedName");
            if (annotation != null) {
                String value = annotation.findAttributeValue("value").getText().replaceAll("\"", "");
                if (value != null) {
                    keys.add(value);
                }
                PsiAnnotationMemberValue altValue = annotation.findAttributeValue("alternate");
                String alternate = altValue == null ? null : altValue.getText();
                if (alternate == null) {

                } else if (alternate.contains("{")) {
                    alternate = alternate.replaceAll("\\{", "");
                    alternate = alternate.replaceAll("}", "");
                    String[] alterKeys = alternate.split(",");
                    if (alterKeys != null) {
                        for (String s : alternate.split(",")) {
                            keys.add(s.trim().replaceAll("\"", ""));
                        }
                    }
                } else {
                    keys.add(alternate.replaceAll("\"", ""));
                }
            }
            fields.add(dartField);
        }
        return dartClass;
    }

    DartClass parseDartClassByField(PsiField field) {
        PsiClass psiClass = ClassUtils.findClass(field.getType().getCanonicalText(), field.getContext());
        String canonicalText = field.getType().getCanonicalText();
        if (canonicalText.contains("[]")) {
            int index = canonicalText.lastIndexOf(".") + 1;
            int end = canonicalText.lastIndexOf("[");
            String type = canonicalText.substring(index, end);
            canonicalText = "java.util.List<" + type + ">";
        }
        if (canonicalText.contains("<")) {
            int start = canonicalText.lastIndexOf("<") + 1;
            int end = canonicalText.lastIndexOf(">");
            String type = canonicalText.substring(start, end);
            psiClass = ClassUtils.findClass(type, field.getContext());
            DartClass dartClass;
            if (psiClass != null && !psiClass.getName().equals("String")) {
                dartClass = dealClass(psiClass);
            } else {
                dartClass = new DartClass(type);
            }
            if (psiClass != null) type = psiClass.getName();
            dartClass.genericity = type;
            dartClass.className = "List<" + type + ">";
            return dartClass;
        }
        if (psiClass == null) {
            return new DartClass(field.getType().getCanonicalText());
        }
        if (field.getType().getCanonicalText().equals("java.lang.String")) {
            return new DartClass("String");
        }
        return dealClass(psiClass);
    }

    private boolean checkFile(PsiFile file) {
        return file instanceof PsiJavaFile;
    }

    static Pattern linePattern = Pattern.compile("_(\\w)");


    public static String lineToHump(String str) {
        if (!str.contains("_")) {
            return str;
        }
        str = str.toLowerCase();
        Matcher matcher = linePattern.matcher(str);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, matcher.group(1).toUpperCase());
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}

class DartClass {

    List<DartField> dartFields;

    String className;

    String genericity;

    DartClass(String className) {
        this.className = className;
    }
}

class DartField {

    DartClass typeClass;

    List<String> keys;

    String name;

}


