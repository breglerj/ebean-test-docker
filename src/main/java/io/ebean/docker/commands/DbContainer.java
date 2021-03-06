package io.ebean.docker.commands;

import io.ebean.docker.commands.process.ProcessHandler;
import io.ebean.docker.container.Container;

import java.io.File;
import java.net.URL;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

abstract class DbContainer extends BaseContainer implements Container {

  enum Mode {
    Create,
    DropCreate,
    ContainerOnly
  }

  final DbConfig dbConfig;

  Mode startMode;

  DbContainer(DbConfig config) {
    super(config);
    this.dbConfig = config;
  }

  /**
   * Log that the container is already running.
   */
  void logRunning() {
    log.info("Container {} running with {} mode:{} shutdown:{}", config.containerName(), dbConfig.summary(), startMode, logShutdown());
  }

  @Override
  void logRun() {
    log.info("Run container {} with {} mode:{} shutdown:{}", config.containerName(), dbConfig.summary(), startMode, logShutdown());
  }

  @Override
  void logStart() {
    log.info("Start container {} with {} mode:{} shutdown:{}", config.containerName(), dbConfig.summary(), startMode, logShutdown());
  }

  private String logShutdown() {
    return dbConfig.shutdownMode == null ? "" : dbConfig.shutdownMode;
  }


  @Override
  public boolean start() {
    return shutdownHook(logStarted(startForMode()));
  }

  /**
   * Start with a mode of 'create', 'dropCreate' or 'container'.
   * <p>
   * Expected that mode create will be best most of the time.
   */
  protected boolean startForMode() {
    String mode = config.getStartMode().toLowerCase().trim();
    switch (mode) {
      case "create":
        return startWithCreate();
      case "dropcreate":
        return startWithDropCreate();
      case "container":
        return startContainerOnly();
      default:
        return startWithCreate();
    }
  }

  /**
   * Start the DB container ensuring the DB and user exist creating them if necessary.
   */
  public boolean startWithCreate() {
    startMode = Mode.Create;
    return startWithConnectivity();
  }

  /**
   * Start the DB container ensuring the DB and user are dropped and then created.
   */
  public boolean startWithDropCreate() {
    startMode = Mode.DropCreate;
    return startWithConnectivity();
  }

  /**
   * Start the container only without creating database, user, extensions etc.
   */
  public boolean startContainerOnly() {
    startMode = Mode.ContainerOnly;
    startIfNeeded();
    if (!waitForDatabaseReady()) {
      log.warn("Failed waitForDatabaseReady for container {}", config.containerName());
      return false;
    }

    if (!waitForConnectivity()) {
      log.warn("Failed waiting for connectivity for {}", config.containerName());
      return false;
    }
    return true;
  }

  /**
   * If we are using FastStartMode just check is the DB exists and if so assume it is all created correctly.
   * <p>
   * This should only be used with Mode.Create and when the container is already running.
   */
  protected boolean fastStart() {
    if (!dbConfig.isFastStartMode()) {
      return false;
    }
    try {
      return isFastStartDatabaseExists();
    } catch (CommandException e) {
      log.debug("failed fast start check - using normal startup");
      return false;
    }
  }

  protected boolean isFastStartDatabaseExists() {
    // return false as by default it is not supported
    return false;
  }

  /**
   * Return the ProcessBuilder used to execute the container run command.
   */
  protected abstract ProcessBuilder runProcess();

  /**
   * Return true when the database is ready to take commands.
   */
  protected abstract boolean isDatabaseReady();

  /**
   * Return true when the database is ready to take admin commands.
   */
  protected abstract boolean isDatabaseAdminReady();

  protected void executeSqlFile(String dbUser, String dbName, String containerFilePath) {
    throw new RuntimeException("executeSqlFile is Not implemented for this platform - Postgres only at this stage");
  }

  /**
   * Return true when the DB is ready for taking commands (like create database, user etc).
   */
  public boolean waitForDatabaseReady() {
    return conditionLoop(this::isDatabaseReady)
      && conditionLoop(this::isDatabaseAdminReady);
  }

  private boolean conditionLoop(BooleanSupplier condition) {
    for (int i = 0; i < config.getMaxReadyAttempts(); i++) {
      try {
        if (condition.getAsBoolean()) {
          return true;
        }
        pause();
      } catch (CommandException e) {
        pause();
      }
    }
    return false;
  }

  private void pause() {
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Check connectivity via trying to make a JDBC connection.
   */
  boolean checkConnectivity() {
    return checkConnectivity(false);
  }

  /**
   * Check connectivity using admin user or dbUser.
   */
  boolean checkConnectivity(boolean useAdmin) {
    try {
      log.debug("checkConnectivity on {} ... ", config.containerName);
      Connection connection = useAdmin ? config.createAdminConnection() : config.createConnection();
      connection.close();
      log.debug("connectivity confirmed for {}", config.containerName);
      return true;

    } catch (SQLException e) {
      log.trace("connection failed: " + e.getMessage());
      return false;
    }
  }

  boolean defined(String val) {
    return val != null && !val.trim().isEmpty();
  }

  void runDbSqlFile(String dbName, String dbUser, String sqlFile) {
    if (defined(sqlFile)) {
      File file = getResourceOrFile(sqlFile);
      if (file != null) {
        runSqlFile(file, dbUser, dbName);
      }
    }
  }

  void runSqlFile(File file, String dbUser, String dbName) {
    if (copyFileToContainer(file)) {
      String containerFilePath = "/tmp/" + file.getName();
      executeSqlFile(dbUser, dbName, containerFilePath);
    }
  }

  File getResourceOrFile(String sqlFile) {

    File file = new File(sqlFile);
    if (!file.exists()) {
      file = checkFileResource(sqlFile);
    }
    if (file == null) {
      log.error("Could not find SQL file. No file exists at location or resource path for: " + sqlFile);
    }
    return file;
  }

  private File checkFileResource(String sqlFile) {
    try {
      if (!sqlFile.startsWith("/")) {
        sqlFile = "/" + sqlFile;
      }
      URL resource = getClass().getResource(sqlFile);
      if (resource != null) {
        File file = Paths.get(resource.toURI()).toFile();
        if (file.exists()) {
          return file;
        }
      }
    } catch (Exception e) {
      log.error("Failed to obtain File from resource for init SQL file: " + sqlFile, e);
    }
    // not found
    return null;
  }

  boolean copyFileToContainer(File sourceFile) {
    ProcessBuilder pb = copyFileToContainerProcess(sourceFile);
    return execute(pb, "Failed to copy file " + sourceFile.getAbsolutePath() + " to container");
  }

  private ProcessBuilder copyFileToContainerProcess(File sourceFile) {

    //docker cp /tmp/init-file.sql ut_postgres:/tmp/init-file.sql
    String dest = config.containerName() + ":/tmp/" + sourceFile.getName();

    List<String> args = new ArrayList<>();
    args.add(config.docker);
    args.add("cp");
    args.add(sourceFile.getAbsolutePath());
    args.add(dest);
    return createProcessBuilder(args);
  }

  /**
   * Execute looking for expected message in stdout with no error logging.
   */
  boolean execute(String expectedLine, ProcessBuilder pb) {
    return execute(expectedLine, pb, null);
  }

  /**
   * Execute looking for expected message in stdout.
   */
  boolean execute(String expectedLine, ProcessBuilder pb, String errorMessage) {
    List<String> outLines = ProcessHandler.process(pb).getOutLines();
    if (!stdoutContains(outLines, expectedLine)) {
      if (errorMessage != null) {
        log.error(errorMessage + " stdOut:" + outLines + " Expected message:" + expectedLine);
      }
      return false;
    }
    return true;
  }

  /**
   * Execute looking for expected message in stdout.
   */
  boolean executeWithout(String errorMatch, ProcessBuilder pb, String errorMessage) {
    List<String> outLines = ProcessHandler.process(pb).getOutLines();
    if (stdoutContains(outLines, errorMatch)) {
      log.error(errorMessage + " stdOut:" + outLines);
      return false;
    }
    return true;
  }

  /**
   * Return true if the stdout contains the expected text.
   */
  boolean stdoutContains(List<String> outLines, String expectedLine) {
    for (String outLine : outLines) {
      if (outLine.contains(expectedLine)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Execute expecting no output to stdout.
   */
  boolean execute(ProcessBuilder pb, String errorMessage) {
    List<String> outLines = ProcessHandler.process(pb).getOutLines();
    if (!outLines.isEmpty()) {
      log.error(errorMessage + " stdOut:" + outLines);
      return false;
    }
    return true;
  }

}
