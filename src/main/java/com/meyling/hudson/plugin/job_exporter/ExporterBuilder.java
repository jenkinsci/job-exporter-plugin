package com.meyling.hudson.plugin.job_exporter;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Build;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Cause;
import hudson.model.Cause.RemoteCause;
import hudson.model.Cause.UpstreamCause;
import hudson.model.Cause.UserIdCause;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Hudson;
import hudson.model.User;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.tasks.Mailer;
import hudson.util.FormValidation;
import hudson.util.LogTaskListener;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import net.sf.json.JSONObject;

import org.apache.commons.lang.ClassUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.lang.time.DateFormatUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

/**
 * Export Jenkins job information into a properties file.
 *
 * <p>
 * When the user configures the project and enables this builder,
 * {@link DescriptorImpl#newInstance(StaplerRequest)} is invoked
 * and a new {@link ExporterBuilder} is created. The created
 * instance is persisted to the project configuration XML by using
 * XStream, so this allows you to use instance fields
 * to remember the configuration.
 *
 * <p>
 * When a build is performed, the {@link #perform(Build, Launcher, BuildListener)} method
 * will be invoked. 
 *
 * @author Michael Meyling
 */
public class ExporterBuilder extends Builder {
    
    private final static String prefix = "build.";

    @DataBoundConstructor
    public ExporterBuilder() {
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
        // this is where you 'build' the project
        final Properties export = new Properties();
        boolean result = true;
        try {
            log(listener, "###################################################################");
            log(listener, "job-exporter plugin  started");
            put(listener, export, "hudson.version", build.getHudsonVersion());
            put(listener, export, "host", Computer.currentComputer().getHostName());
            put(listener, export, "id", build.getId());
            put(listener, export, "duration", build.getTimestampString());
            put(listener, export, "slave", build.getBuiltOnStr());
            put(listener, export, "started", DateFormatUtils.ISO_DATETIME_FORMAT.format(build.getTimestamp()));
            put(listener, export, "result", Result.SUCCESS.toString()); // set default
            if (build.getResult() != null) {
                put(listener, export, "result", build.getResult().toString());
            }
            final Executor executor = build.getExecutor();
            if (executor != null) {
                put(listener, export, "summary", executor.getName());
                put(listener, export, "executor", "" + executor.getNumber());
                put(listener, export, "elapsedTime", "" + executor.getElapsedTime());
            }
            put(listener, export, "number", "" + build.getNumber());
            final EnvVars env = build.getEnvironment(new LogTaskListener(Logger.getLogger(
                this.getClass().getName()), Level.INFO));
            
            if (env != null) {
                putIfNotNull(listener, export, "jobName", env.get("JOB_NAME"));
                putIfNotNull(listener, export, "cvsBranch", env.get("CVS_BRANCH"));
                putIfNotNull(listener, export, "svnRevision", env.get("SVN_REVISION"));
                putIfNotNull(listener, export, "gitBranch", env.get("GIT_BRANCH")); 
            }
            
            final Mailer.DescriptorImpl descriptor
                = (Mailer.DescriptorImpl) Hudson.getInstance().getDescriptorByType(
                Mailer.DescriptorImpl.class);
            export.put(prefix + "admin.emailAddress", descriptor.getAdminAddress());
            final List<Cause> cl = build.getCauses();
            log(listener, "  we have " + cl.size() + " build cause"
                + (cl.size() > 1 ? "s" : "") + (cl.size() > 0 ? ":" : ""));
            for (final Cause c : cl) {
                log(listener, "      " + ClassUtils.getShortClassName(c.getClass())
                    + "  " + c.getShortDescription());
                if (c instanceof UserIdCause) {
                    // set admin email address as fallback, if we don't get an user email address 
                    export.put(prefix + "user.emailAddress", descriptor.getAdminAddress());
                    final UserIdCause uc = (UserIdCause) c;
                    final String userId = uc.getUserId();
                    put(listener, export, "user.id", StringUtils.defaultIfEmpty(userId, "null"));
                    put(listener, export, "user.name", uc.getUserName());
                    try {
                        final User u = User.get(userId);
                        if (u != null) {
                            put(listener, export, "user.fullName", u.getFullName());
                            final Mailer.UserProperty umail = u.getProperty(Mailer.UserProperty.class);
                            put(listener, export, "user.emailAddress", StringUtils.trimToEmpty(umail.getAddress()));
                        }
                    } catch (Exception e) {
                        log(listener, e);
                    }
                } else if (c instanceof UpstreamCause) {
                    final UpstreamCause uc = (UpstreamCause) c;
                    put(listener, export, "upstream.number", "" + uc.getUpstreamBuild());
                    put(listener, export, "upstream.project", uc.getUpstreamProject());
                } else if (c instanceof RemoteCause) {
                    final RemoteCause rc = (RemoteCause) c;
                    String remoteHost = rc.getShortDescription();   // fallback
                    try {
                        remoteHost = (String) getFieldValue(rc, "host");
                    } catch (Exception e) {
                        log(listener, e);
                    }
                    put(listener, export, "remote.host", remoteHost);
                    String note = rc.getShortDescription();         // fallback
                    try {
                        note = (String) getFieldValue(rc, "note");
                    } catch (Exception e) {
                        log(listener, e);
                    }
                    put(listener, export, "remote.note", note);
                }
            }
        } catch (Exception e) {
            e.printStackTrace(listener.error("failure during property evaluation"));
            build.setResult(Result.FAILURE);
            result = false;
        }
        OutputStream out = null;
        try {
            final FilePath ws = build.getWorkspace();
            final FilePath hudson = ws.child("hudsonBuild.properties");
            if (hudson.exists()) {
                if (!hudson.delete()) {
                    listener.error("old file can not be deleted: " + hudson);
                    build.setResult(Result.FAILURE);
                    return false;
                } else {
                    log(listener, "  old file deleted: " + hudson);
                }
            }
            out = hudson.write();
            export.store(out, "created by " + this.getClass().getName());
            out.close();
            log(listener, "  new file written: " + hudson);
        } catch (Exception e) {
            e.printStackTrace(listener.error("failed to read or write property file"));
            build.setResult(Result.FAILURE);
            result = false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    // ignore
                }
            }
            log(listener, "job-exporter plugin  finished.  That's All Folks!");
            log(listener, "###################################################################");
        }
        return result;
    }

    protected void put(final BuildListener listener, final Properties export, final String key, final String value) {
        export.put(prefix + key, StringUtils.defaultString(value));
        log(listener, "    " + key + ": " + StringUtils.defaultString(value));
    }

    protected void putIfNotNull(final BuildListener listener, final Properties export, final String key, final String value) {
        if (value != null) {
            put(listener, export, key, value);
        }
    }

    protected void log(final BuildListener listener, final String message) {
        final PrintStream logger = listener.getLogger();
        logger.println(StringUtils.defaultString(message));
    }

    protected void log(final BuildListener listener, final Exception e) {
        final PrintStream logger = listener.getLogger();
        logger.println(ExceptionUtils.getStackTrace(e));
    }

    
    // overrided for better type safety.
    // if your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link ExporterBuilder}.
     */
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        
//        To persist global configuration information,
//        simply store it in a field and call save().
//        If you don't want fields to be persisted, use <tt>transient</tt>.

        /**
         * Performs on-the-fly validation of the form field 'name'.
         *
         * @param value
         *      This parameter receives the value that the user has typed.
         * @return
         *      Indicates the outcome of the validation. This is sent to the browser.
         */
        public FormValidation doCheckName(@QueryParameter String value) throws IOException, ServletException {
            if(value.length()==0)
                return FormValidation.error("Please set a name");
            if(value.length()<4)
                return FormValidation.warning("Isn't the name too short?");
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // indicates that this builder can be used with all kinds of project types 
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "export job runtime parameters";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject o) throws FormException {
            // to persist global configuration information,
            // set that to properties and call save().
            // still nothing to do for us
            save();
            return super.configure(req, o);
        }

    }
    
    /**
     * We learned so much from the great Jedi master. Using the force we can get and set private
     * fields of arbitrary objects.
     * This method returns the contents of an object variable. The class hierarchy is recursively
     * searched to find such a field (even if it is private).
     *
     * @param   obj     Object.
     * @param   name    Variable name.
     * @return  Contents of variable.
     * @throws  NoSuchFieldException    Variable of given name was not found.
     */
    public static Object getFieldValue(final Object obj, final String name) throws NoSuchFieldException {
        final Field field = getField(obj, name);
        try {
            return field.get(obj);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get field of given name in given object. The class hierarchy is recursively searched
     * to find such a field (even if it is private).
     *
     * @param   obj     Object to work on.
     * @param   name    Search this field.
     * @return  Found field.
     * @throws  NoSuchFieldException    Field with name <code>name</code> was not found.
     */
    @SuppressWarnings("unchecked")
    public static Field getField(final Object obj, final String name) throws NoSuchFieldException {
        Field field = null;
        try {
            Class cl = obj.getClass();
            while (!Object.class.equals(cl)) {
                try {
                    field = cl.getDeclaredField(name);
                    break;
                } catch (NoSuchFieldException ex) {
                    cl = cl.getSuperclass();
                }
            }
            if (field == null) {
                throw (new NoSuchFieldException(name + " within " + obj.getClass()));
            }
            field.setAccessible(true);
        } catch (SecurityException e) {
            throw new RuntimeException(e);
        }
        return field;
    }

}
