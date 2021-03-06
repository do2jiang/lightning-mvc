package cn.fh.lightning.mvc.servlet;

import cn.fh.lightning.StringUtil;
import cn.fh.lightning.bean.container.BasicInjectableBeanContainer;
import cn.fh.lightning.bean.container.InjectableBeanContainer;
import cn.fh.lightning.bean.scan.AsynClasspathPackageScanner;
import cn.fh.lightning.bean.scan.DefaultAnnotationProcessor;
import cn.fh.lightning.bean.scan.PackageScanner;
import cn.fh.lightning.exception.BeanNotFoundException;
import cn.fh.lightning.mvc.BasicModel;
import cn.fh.lightning.mvc.Constants;
import cn.fh.lightning.mvc.Controller;
import cn.fh.lightning.mvc.InternalModel;
import cn.fh.lightning.mvc.exception.InvalidControllerException;
import cn.fh.lightning.mvc.exception.ViewNotFoundException;
import cn.fh.lightning.mvc.requestmapping.RequestMapping;
import cn.fh.lightning.mvc.requestmapping.RequestType;
import cn.fh.lightning.mvc.requestmapping.UrlRequestMapping;
import cn.fh.lightning.resource.Reader;
import cn.fh.lightning.resource.WebXmlReader;
import cn.fh.lightning.resource.XmlReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * This is the main servlet that starts the lightning-mvc framework.
 */
public class LightningServlet extends BasicServlet implements ServletContextListener {
	public static Logger logger = LoggerFactory.getLogger(LightningServlet.class);

	public static String CONFIGURE_FILE_ATTRIBUTE = "CONFIGURE_FILE_LOCATION";
	public static String BEAN_CONTAINER_ATTRIBUTE = "BEAN_CONTAINER_ATTRIBUTE";
	
	public static String DEFAULT_CONFIGURE_FILE_LOCATION = "/WEB-INF/lightning-config.xml";
	public static String DEFAULT_WEB_CONFIGURE_FILE_LOCATION = "/WEB-INF/lightning-url-map.xml";

    // These are the context parameters specified in web.xml
    public static String INIT_PARM_ENABLE_COMPONENT_SCAN = "ENABLE_COMPONENT_SCAN";
    public static String INIT_PARM_SCAN_PACKAGE = "SCAN_PACKAGE";

	private RequestMapping[] reqMaps;

    protected String ENABLE_COMPONENT_SCAN = "FALSE";
    protected String SCAN_BASE_PACKAGE = "cn.fh.sample";
	
	@Override
	public void contextDestroyed(ServletContextEvent arg0) {
		// TODO Auto-generated method stub
		
	}

	/**
     * Invoked when the application is deployed to the web server.
     * This method is responsible to do the following tasks:
     * <p><ol>
     *     <li>Load web security configurations</li>
     *     <li>Initialize IoC container</li>
     *     <li>Find components from configuration files or classpath</li>
     *     </ol>
     *
	 */
	@Override
	public void contextInitialized(ServletContextEvent event) {
        // get the path of the configuration file in web.xml
		String configFile = event.getServletContext().getInitParameter(CONFIGURE_FILE_ATTRIBUTE);
		if (null == configFile) {
            // use default path
			configFile = DEFAULT_CONFIGURE_FILE_LOCATION;
		}


        // load context parameters specified in web.xml
        loadContextParameters(event.getServletContext());

        // parse configuration file
		logger.info("正在解析bean配置文件");
		Reader reader = new XmlReader(event.getServletContext(), configFile);
        // create IoC container instance
		logger.info("正在启动IoC容器");
		InjectableBeanContainer ioc = new BasicInjectableBeanContainer();
        // register beans parsed from configuration file
		ioc.registerBeans(reader.loadBeans());
        // put IoC container into servlet context
		event.getServletContext().setAttribute(BEAN_CONTAINER_ATTRIBUTE, ioc);
        logger.info("容器启动完毕");

        // start to scan package to find out more component
        PackageScanner scanner = null;
        if (Boolean.valueOf(ENABLE_COMPONENT_SCAN)) {
            logger.info("已开启组件自动扫描(ENABLE_COMPONENT_SCAN = true)");
            try {
                scanner = startPackageScan(event.getServletContext(), SCAN_BASE_PACKAGE);
            } catch (IOException e) {
                logger.error("扫描组件失败");
                e.printStackTrace();
            }
        }


		// load security configurations
        loadSecurityConfigurations(event.getServletContext());


        // Package scan should be finished now.
        // If not, keep looping until it finishes.
        if (Boolean.valueOf(ENABLE_COMPONENT_SCAN)) {
            try {
                while (!registerComponents(event.getServletContext(), scanner));
            } catch (IOException e) {
                logger.error("扫描组件失败");
                e.printStackTrace();
            }
        }

        logger.info("Lightning-mvc初始化完成");

    }

    private void loadSecurityConfigurations(ServletContext ctx) {
        Reader reader = new WebXmlReader(ctx, DEFAULT_WEB_CONFIGURE_FILE_LOCATION);
        this.reqMaps = reader.loadBeans().toArray(new UrlRequestMapping[1]);
        logger.info("加载url映射完毕");
    }

    private PackageScanner startPackageScan(ServletContext ctx, String packageName) throws IOException {
        return new AsynClasspathPackageScanner(packageName, ctx);
    }

	private boolean registerComponents(ServletContext ctx, PackageScanner scanner) throws IOException {
        List<String> nameList = scanner.getCanonicalNameList();

        // scanning has not finished yet
        if (null == nameList || nameList.isEmpty()) {
            return false;
        }

        for (String className : nameList) {
            try {
                Class clazz = Class.forName(className);
                new DefaultAnnotationProcessor(clazz, getContainer(ctx));
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
        }

        return true;
	}


    /**
     * This method actually handles the request.
     *
     * @param reqMethod The type of this request, could be GET or POST.
     */
	@Override
	protected void processRequest(HttpServletRequest req, HttpServletResponse resp, Constants.RequestMethod reqMethod) {
		// 得到请求类型
		RequestType reqType = RequestType.valueOf(req.getMethod());
		
		String requestPath = StringUtil.trimURI(req.getRequestURI());
		
		if (logger.isDebugEnabled()) {
			logger.debug("收到请求[" + requestPath + "]");
		}

        // find out the request mapping handler that is supposed to handle this request
		RequestMapping rMap = findController(requestPath, reqType);
		if (null == rMap) {
			logger.info("没有与[" + requestPath + "]对应的控制器!");
			return;
		}

        // get the name of the controller that the request mapping handler is bound with
		String controllerName = rMap.getControllerName();
        // retrieve controller from container
		Object bean = getContainer(req).getBeanWithDependencies(controllerName);
		if (null == bean) {
			throw new BeanNotFoundException("没有找到[" + controllerName + "]");
		}
		//Object cl = bean.getActuallBean();
		if (false == bean instanceof Controller) {
			throw new InvalidControllerException("Controller[" + controllerName + "]非法:没有实现Controller接口");
		}

        // create model
		InternalModel model = new BasicModel();
		Controller controller = (Controller)bean;
        // invoke handle method
		String viewName = controller.handle(req, model);

		

        // transfer data from model to request attribute
		if (false == model.getAttrMap().isEmpty()) {
			for (Map.Entry<String, Object> entry : model.getAttrMap().entrySet()) {
				req.setAttribute(entry.getKey(), entry.getValue());
			}
		}

        // forward request
		if (null == viewName) {
			throw new ViewNotFoundException("视图名不能为空!");
		}
		try {
			req.getServletContext().getRequestDispatcher("/WEB-INF/views/" + viewName).forward(req, resp);
		} catch (ServletException | IOException e) {
			throw new ViewNotFoundException("找不到[" + viewName + "]");
		}
	}
	
	/**
     * Find the {@link cn.fh.lightning.mvc.requestmapping.RequestMapping} implementation that contains controller that
     * is supposed to handle this request
	 *
	 * @return The implementation of {@link cn.fh.lightning.mvc.requestmapping.RequestMapping} interface
	 */
	private RequestMapping findController(String url, RequestType reqType) {
		for (RequestMapping rMap : this.reqMaps) {
			if (rMap.getUrl().equals(url) && rMap.getRequestType() == reqType) {
				return rMap;
			}
		}
		
		return null;
	}

	/**
     * Do initialization jobs.
     * This method will be invoked when this servlet is created.
	 */
	@Override
	protected void initServlet(ServletConfig cfg) {
        // load request mapping configuration from configuration file
		//initRequestMapping(cfg.getServletContext());
		//logger.info("加载url映射完毕");
	}

    private void loadContextParameters(ServletContext ctx) {
        String compScan = ctx.getInitParameter(LightningServlet.INIT_PARM_ENABLE_COMPONENT_SCAN);
        if (null != compScan) {
            this.ENABLE_COMPONENT_SCAN = compScan;
        }

        String basePackage = ctx.getInitParameter(LightningServlet.INIT_PARM_SCAN_PACKAGE);
        if (basePackage != null) {
            this.SCAN_BASE_PACKAGE = basePackage;
        }
    }

    /**
     * @deprecated
     */
	private void initRequestMapping(ServletContext ctx) {
		Reader reader = new WebXmlReader(ctx, DEFAULT_WEB_CONFIGURE_FILE_LOCATION);
		this.reqMaps = reader.loadBeans().toArray(new UrlRequestMapping[1]);
		//getContainer().registerBeans(reader.loadBeans());
	}
	
	private InjectableBeanContainer getContainer(HttpServletRequest req) {
		return (InjectableBeanContainer)req.getServletContext().getAttribute(BEAN_CONTAINER_ATTRIBUTE);
	}

    /**
     * Retrieve container from ServletContext
     */
    private InjectableBeanContainer getContainer(ServletContext ctx) {
        return (InjectableBeanContainer) ctx.getAttribute(BEAN_CONTAINER_ATTRIBUTE);
    }

}
