package cn.fh.lightning.bean.scan;

import cn.fh.lightning.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

/**
 * Created by whf on 15-2-26.
 */
public class ClasspathPackageScanner implements PackageScanner {
    private Logger logger = LoggerFactory.getLogger(ClasspathPackageScanner.class);

    private String basePackage;
    private ClassLoader cl;

    /**
     * Construct an instance and specify the base package it should scan.
     * @param basePackage The base package to scan.
     */
    public ClasspathPackageScanner(String basePackage) {
        this.basePackage = basePackage;
        this.cl = getClass().getClassLoader();

    }

    /**
     * Construct an instance with base package and class loader.
     * @param basePackage The base package to scan.
     * @param cl Use this class load to locate the package.
     */
    public ClasspathPackageScanner(String basePackage, ClassLoader cl) {
        this.basePackage = basePackage;
        this.cl = cl;
    }

    /**
     * Get all fully qualified names located in the specified package
     * and its sub-package.
     *
     * @return A list of fully qualified names.
     * @throws IOException
     */
    @Override
    public List<String> getFullyQualifiedClassNameList() throws IOException {
        logger.info("开始扫描包{}下的所有类", basePackage);

        return doScan(basePackage, new ArrayList<>());
    }

    /**
     * Actually perform the scanning procedure.
     *
     * @param basePackage
     * @param nameList A list to contain the result.
     * @return A list of fully qualified names.
     *
     * @throws IOException
     */
    private List<String> doScan(String basePackage, List<String> nameList) throws IOException {
        // replace dots with splashes
        String splashPath = StringUtil.dotToSplash(basePackage);

        // get file path
        URL url = cl.getResource(splashPath);
        String filePath = StringUtil.getRootPath(url);

        // get classes in that package
        List<String> names = null;
        if (isJarFile(filePath)) {
            if (logger.isDebugEnabled()) {
                logger.debug("{} 是一个JAR包", filePath);
            }

            names = readFromJarFile(filePath, splashPath);
        } else {
            // directory
            if (logger.isDebugEnabled()) {
                logger.debug("{} 是一个目录", filePath);
            }

            names = readFromDirectory(filePath);
        }

        for (String name : names) {
            if (isClassFile(name)) {
                nameList.add(basePackage + "." + StringUtil.trimExtension(name));
            } else {
                // this is a directory
                // check this directory for more classes
                // do recursive invocation
                doScan(basePackage + "." + name, nameList);
            }
        }

        if (logger.isDebugEnabled()) {
            for (String n : nameList) {
                logger.debug("找到{}", n);
            }
        }

        return nameList;
    }

    private List<String> readFromJarFile(String jarPath, String splashedPackageName) throws IOException {
        if (logger.isDebugEnabled()) {
            logger.debug("从JAR包中读取类: {}", jarPath);
        }

        JarInputStream jarIn = new JarInputStream(new FileInputStream(jarPath));
        JarEntry entry = jarIn.getNextJarEntry();

        List<String> nameList = new ArrayList<>();
        while (null != entry) {
            String name = entry.getName();
            if (name.startsWith(splashedPackageName) && isClassFile(name)) {
                nameList.add(name);
            }

            entry = jarIn.getNextJarEntry();
        }

        return nameList;
    }

    private List<String> readFromDirectory(String path) {
        File file = new File(path);
        String[] names = file.list();

        if (null == names) {
            return null;
        }

        return Arrays.asList(names);
    }

    private boolean isClassFile(String name) {
        return name.endsWith(".class");
    }

    private boolean isJarFile(String name) {
        return name.endsWith(".jar");
    }

    /**
     * For test purpose.
     */
    public static void main(String[] args) throws Exception {
        PackageScanner scan = new ClasspathPackageScanner("cn.fh.lightning.bean");
        scan.getFullyQualifiedClassNameList();
    }

}
