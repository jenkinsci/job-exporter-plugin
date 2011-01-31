package com.meyling.hudson.plugin.job_exporter;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Build;
import hudson.model.BuildListener;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Hudson;
import hudson.model.Result;
import hudson.model.User;
import hudson.model.Cause.RemoteCause;
import hudson.model.Cause.UpstreamCause;
import hudson.model.Cause.UserCause;
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

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

/**
 * Export hudson job information into a properties file.
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
    
    @DataBoundConstructor
    public ExporterBuilder() {
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
        // this is where you 'build' the project
        String prefix = "build.";
        Properties export = new Properties();
        try {
            System.out.println(Computer.currentComputer().getHostName());
            export.put(prefix + "hudson.version", StringUtils.defaultString(build.getHudsonVersion()));
            export.put(prefix + "id", StringUtils.defaultString(build.getId()));
            Executor executor = build.getExecutor();
            if (executor != null) {
                export.put(prefix + "summary", StringUtils.defaultString(build.getExecutor().getName()));
            }
            export.put(prefix + "number", "" + build.getNumber());
            
            EnvVars env = build.getEnvironment(new LogTaskListener(Logger.getLogger(this.getClass().getName()), Level.INFO));
            
            if (env != null) {
                export.put(prefix + "jobName", StringUtils.defaultString(env.get("JOB_NAME")));
                export.put(prefix + "cvsBranch", StringUtils.defaultString(env.get("CVS_BRANCH")));
            }
            log(listener.getLogger(), "exporting properties");
            
            Mailer.DescriptorImpl descriptor = (Mailer.DescriptorImpl) Hudson.getInstance().getDescriptorByType(
                Mailer.DescriptorImpl.class);
            export.put(prefix + "admin.emailAddress", descriptor.getAdminAddress());

            // now we iterate over the cause actions to identify the user and get his properties
            List<CauseAction> cal = build.getActions(CauseAction.class);
            for (CauseAction ca : cal) {
                log(listener.getLogger(), ca.getDisplayName());
                log(listener.getLogger(), ca.toString());
                List<Cause> cl = ca.getCauses();
                for (Cause c : cl) {
                    if (c instanceof UserCause) {
                        // set admin email address as fallback, if we don't get a user email address 
                        export.put(prefix + "user.emailAddress", descriptor.getAdminAddress());
                        final UserCause uc = (UserCause) c;
                        export.put(prefix + "user.name", uc.getUserName());
                        String authenticationName = Hudson.getAuthentication().getName();
                        try {
                            authenticationName = (String) getFieldValue(uc, "authenticationName");
                        } catch (Exception e) {
                            // ignore
                        }
                        export.put(prefix + "user.name", StringUtils.defaultString(authenticationName));
                        try {
                            User u = User.get(authenticationName);
                            export.put(prefix + "user.fullName", StringUtils.defaultString(u.getFullName()));
                            Mailer.UserProperty umail = u.getProperty(Mailer.UserProperty.class);
                            String email = StringUtils.defaultString(umail.getAddress()).trim();
                            if (email.length() > 0) {
                                export.put(prefix + "user.emailAddress", email);
                            }
                        } catch (Exception e) {
                            // ignore
                        }
                    } else if (c instanceof UpstreamCause) {
                        final UpstreamCause uc = (UpstreamCause) c; 
                        export.put(prefix + "upstream.number", "" + uc.getUpstreamBuild());
                        export.put(prefix + "upstream.project", StringUtils.defaultString(uc.getUpstreamProject()));
                    } else if (c instanceof RemoteCause) {
                        final RemoteCause uc = (RemoteCause) c;
                        String host = uc.getShortDescription(); // fallback
                        try {
                            host = (String) getFieldValue(uc, "host");
                        } catch (Exception e) {
                            // ignore
                        }
                        export.put(prefix + "remote.host", "" + host);
                        String note = uc.getShortDescription(); // fallback
                        try {
                            note = (String) getFieldValue(uc, "note");
                        } catch (Exception e) {
                            // ignore
                        }
                        export.put(prefix + "remote.note", "" + note);
                    }
                }
            }
            FilePath ws = build.getWorkspace();
            FilePath hudson = ws.child("hudsonBuild.properties");
            if (hudson.exists()) {
                if (!hudson.delete()) {
                    log(listener.getLogger(), "old file can not be deleted: " + hudson);
                    build.setResult(Result.FAILURE);
                    return false;
                } else {
                    log(listener.getLogger(), "old file deleted: " + hudson);
                }
            }
            final OutputStream out = hudson.write();
            export.store(out, "created by " + this.getClass().getName());
            out.close();
            log(listener.getLogger(), "new properties file written: " + hudson);
        } catch (IOException e) {
            e.printStackTrace(listener.error("failed to read or write property file"));
            build.setResult(Result.FAILURE);
            return false;
        } catch (InterruptedException e) {
            e.printStackTrace(listener.error("failed to read or write property file"));
            build.setResult(Result.FAILURE);
            return false;
        }
        return true;
    }

    protected void log(final PrintStream logger, final String message) {
        logger.println("[exporter] " + StringUtils.defaultString(message));
    }

    
    // overrided for better type safety.
    // if your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link ExporterBuilder}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     * <p>
     * See <tt>views/hudson/plugins/exporter/ExporterBuilder/*.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension // this marker indicates Hudson that this is an implementation of an extension point.
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
            return "Export job runtime parameters";
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
