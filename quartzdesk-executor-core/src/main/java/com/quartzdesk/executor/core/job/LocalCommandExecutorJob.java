/*
 * Copyright (c) 2015-2019 QuartzDesk.com.
 * Licensed under the MIT license (https://opensource.org/licenses/MIT).
 */

package com.quartzdesk.executor.core.job;

import com.quartzdesk.executor.common.text.StringUtils;
import com.quartzdesk.executor.core.CommonConst;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.UnableToInterruptJobException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Quartz job implementation that executes an arbitrary local command/script specified in a job data map parameter.
 * The following job data map parameters are supported:
 *
 * <dl>
 * <dt>command</dt>
 * <dd>The command to execute.</dd>
 *
 * <dt>commandArgs</dt>
 * <dd>Optional space-separated command line arguments to pass to the command. An argument that contains spaces and
 * which should be treated as a single argument must be enclosed in double-quotes.</dd>
 *
 * <dt>commandWorkDir</dt>
 * <dd>An optional work directory for the command.</dd>
 * </dl>
 */
@DisallowConcurrentExecution
public class LocalCommandExecutorJob
    extends AbstractJob
    implements InterruptableJob
{
  private static final Logger log = LoggerFactory.getLogger( LocalCommandExecutorJob.class );

  private static final String JDM_KEY_COMMAND = "command";

  private static final String JDM_KEY_COMMAND_ARGS = "commandArgs";

  private static final String JDM_KEY_COMMAND_WORK_DIR = "commandWorkDir";

  /**
   * Maximum number of attempts to stop the started native process.
   */
  private static final int MAX_PROCESS_DESTROY_ATTEMPTS = 10;

  private static final String PROCESS_OUTPUT_EXECUTOR_BEAN_NAME = "processOutputExecutor";

  private Process process;


  @Override
  public void interrupt()
      throws UnableToInterruptJobException
  {
    log.info( "Received interrupt request to stop this job." );

    if ( process == null )
    {
      log.warn( "The native process has not been started yet." );
      throw new UnableToInterruptJobException( "Cannot kill the native process because it has not been started." );
    }
    else
    {
      Long processPid = getProcessPid( process );

      int attemptCount = 0;
      while ( attemptCount < MAX_PROCESS_DESTROY_ATTEMPTS && isProcessAlive( process ) )
      {
        attemptCount++;

        log.info( "Attempting to kill the started native process: {}. Attempt count: {}", process, attemptCount );
        process.destroy();

        try
        {
          Thread.sleep( 1000 );
        }
        catch ( InterruptedException e )
        {
          //
        }
      }

      if ( isProcessAlive( process ) )
      {
        log.warn( "Failed to kill the started native process: {}", process );
        if ( processPid == null )
        {
          throw new UnableToInterruptJobException(
              "Cannot kill the started native process. Please kill the process in the operating system to stop this job." );
        }
        else
        {
          throw new UnableToInterruptJobException( "Cannot kill the started native process [pid=" + processPid +
              "]. Please kill the process in the operating system to stop this job." );
        }
      }
      else
      {
        log.info( "Successfully killed the started native process: {}", process );
      }
    }
  }


  @Override
  protected void executeJob( JobExecutionContext context )
      throws JobExecutionException
  {
    log.debug( "Inside job: {}", context.getJobDetail().getKey() );

    JobDataMap jobDataMap = context.getMergedJobDataMap();

    // command
    String command = jobDataMap.getString( JDM_KEY_COMMAND );
    if ( command == null )
    {
      throw new JobExecutionException( "Missing required '" + JDM_KEY_COMMAND + "' job data map parameter." );
    }

    // command arguments (optional)
    String commandArgs = jobDataMap.getString( JDM_KEY_COMMAND_ARGS );

    // command work directory (optional)
    String commandWorkDir = jobDataMap.getString( JDM_KEY_COMMAND_WORK_DIR );
    File commandWorkDirFile = null;
    if ( commandWorkDir != null )
    {
      commandWorkDirFile = new File( commandWorkDir );

      if ( !commandWorkDirFile.exists() || !commandWorkDirFile.isDirectory() )
      {
        throw new JobExecutionException(
            "Command work directory '" + commandWorkDirFile.getAbsolutePath() + "' specified in the '" +
                JDM_KEY_COMMAND_WORK_DIR +
                "' job data map parameter does not exist." );
      }
    }

    // execute the command
    List<String> commandLine = prepareCommandLine( command, commandArgs );
    ProcessBuilder processBuilder = new ProcessBuilder( commandLine );

    processBuilder.redirectErrorStream( true );

    // set the process work directory if specified; otherwise the default work directory is used
    if ( commandWorkDirFile != null )
    {
      processBuilder.directory( commandWorkDirFile );
    }

    // we could possibly set the process environment here
    //processBuilder.environment()

    try
    {
      log.info( "Executing local command using command line: {}", commandLine );

      ExecutorService standardOutputExecutor = getProcessOutputExecutor( context );

      process = processBuilder.start();

      StandardOutputReaderCallable stdOutCallable = new StandardOutputReaderCallable( process.getInputStream() );
      Future<String> stdOutDataFuture = standardOutputExecutor.submit( stdOutCallable );

      int exitCode = process.waitFor();  // wait for the process to finish

      log.debug( "Local command finished with exit code: {}", exitCode );
      context.setResult( exitCode );  // exit code is used as the job's execution result (visible in the QuartzDesk GUI)

      try
      {
        String output = stdOutDataFuture.get();
        if ( StringUtils.isBlank( output ) )
        {
          log.info( "Local command produced no output." );
        }
        else
        {
          log.info( "Local command produced the following output:{}{}", CommonConst.NL, output );
        }
      }
      catch ( Exception e )   // CancellationException, ExecutionException, InterruptedException
      {
        log.warn( "Error getting process data.", e );
      }

      // if result != 0, we typically want to throw JobExecutionException indicating a job execution failure
      if ( exitCode != 0 )
      {
        throw new JobExecutionException( "Command finished with non-zero exit code: " + exitCode );
      }
    }
    catch ( IOException e )
    {
      throw new JobExecutionException( "Error starting command process.", e );
    }
    catch ( InterruptedException e )
    {
      throw new JobExecutionException( "Command process has been interrupted.", e );
    }
  }


  /**
   * Returns the {@link ExecutorService} instance to be used to read process standard and error
   * output data.
   *
   * @param context the job execution context.
   * @return the {@link ExecutorService} instance.
   */
  private ExecutorService getProcessOutputExecutor( JobExecutionContext context )
  {
    ApplicationContext appCtx = getApplicationContext( context );
    return appCtx.getBean( PROCESS_OUTPUT_EXECUTOR_BEAN_NAME, ExecutorService.class );
  }


  /**
   * Returns the PID of the specified process.
   *
   * @param process a process.
   * @return the PID or null if not available.
   */
  private Long getProcessPid( Process process )
  {
    try
    {
      Method pidMethod = Process.class.getDeclaredMethod( "pid" );
      return (Long) pidMethod.invoke( process );
    }
    catch ( NoSuchMethodException e )
    {
      // Process.pid method is available from Java 9
      return null;
    }
    catch ( IllegalAccessException | InvocationTargetException e )
    {
      // pid method cannot be invoked for some reason
      return null;
    }
    catch ( UnsupportedOperationException e )
    {
      // Process.pid method is available, but is not supported on this platform
      return null;
    }
  }


  /**
   * Returns true if the specified process is alive (is still running), false otherwise.
   *
   * @param process a process.
   * @return true if the specified process is alive (is still running), false otherwise.ø
   */
  private boolean isProcessAlive( Process process )
  {
    try
    {
      process.exitValue();  // throws exception if not finished
      return false;
    }
    catch ( IllegalThreadStateException e )
    {
      return true;
    }
  }


  /**
   * Prepares the process command line to execute the specified command with the specified arguments.
   *
   * @param commandPath a command path.
   * @param commandArgs concatenated command arguments separated by spaces.
   * @return the command line.
   */
  private List<String> prepareCommandLine( String commandPath, String commandArgs )
  {
    List<String> commandLine = new ArrayList<String>();
    commandLine.add( commandPath );

    if ( commandArgs != null )
    {
      //
      // Parse commandArgs value. Sequences enclosed in double-quotes are treated as a single argument.
      //
      Matcher matcher = Pattern.compile( "\"([^\"]*)\"|'([^']*)'|[^\\s]+" ).matcher( commandArgs );
      while ( matcher.find() )
      {
        if ( matcher.group( 1 ) != null )
        {
          // Add double-quoted string without the quotes
          commandLine.add( matcher.group( 1 ) );
        }
        else if ( matcher.group( 2 ) != null )
        {
          // Add single-quoted string without the quotes
          commandLine.add( matcher.group( 2 ) );
        }
        else
        {
          // Add unquoted word
          commandLine.add( matcher.group() );
        }
      }
    }

    return commandLine;
  }


  /**
   * Runnable wrapper around the specified standard output stream that reads data
   * written to the output stream and writes them to the log using the INFO priority.
   */
  private static class StandardOutputReaderCallable
      implements Callable<String>
  {
    private BufferedReader reader;


    private StandardOutputReaderCallable( InputStream ins )
    {
      reader = new BufferedReader( new InputStreamReader( ins ) );
    }


    @Override
    public String call()
    {
      StringBuilder data = new StringBuilder();

      try
      {
        String line;
        while ( ( line = reader.readLine() ) != null )
        {
          data.append( line ).append( CommonConst.NL );
          //log.info( line );
        }
      }
      catch ( IOException e )
      {
        log.error( "Error reading from reader: " + reader, e );
      }
      finally
      {
        try
        {
          reader.close();
        }
        catch ( IOException e )
        {
          log.error( "Error closing reader: " + reader, e );
        }
      }

      return data.length() == 0 ? null : data.toString();
    }
  }
}
