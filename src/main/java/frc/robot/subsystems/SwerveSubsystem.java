package frc.robot.subsystems;

import static edu.wpi.first.units.Units.Meters;
import static edu.wpi.first.units.Units.MetersPerSecond;
import static edu.wpi.first.units.Units.Second;
import static edu.wpi.first.units.Units.Volts;

import com.kauailabs.navx.frc.AHRS;
import com.pathplanner.lib.auto.AutoBuilder;
import com.pathplanner.lib.path.PathPlannerPath;
import com.pathplanner.lib.util.PathPlannerLogging;

import edu.wpi.first.math.Matrix;
import edu.wpi.first.math.VecBuilder;
import edu.wpi.first.math.controller.PIDController;
import edu.wpi.first.math.estimator.SwerveDrivePoseEstimator;
import edu.wpi.first.math.filter.LinearFilter;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.math.geometry.Twist2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.math.kinematics.SwerveDriveKinematics;
import edu.wpi.first.math.kinematics.SwerveModulePosition;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import edu.wpi.first.math.numbers.N1;
import edu.wpi.first.math.numbers.N3;
import edu.wpi.first.math.util.Units;
import edu.wpi.first.wpilibj.BuiltInAccelerometer;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.DriverStation.Alliance;
import edu.wpi.first.wpilibj.RobotBase;
import edu.wpi.first.wpilibj.SPI;
import edu.wpi.first.wpilibj.Timer;
import edu.wpi.first.wpilibj.smartdashboard.Field2d;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Commands;
import edu.wpi.first.wpilibj2.command.SubsystemBase;
import edu.wpi.first.wpilibj2.command.sysid.SysIdRoutine;
import edu.wpi.first.wpilibj2.command.sysid.SysIdRoutine.Direction;
import frc.robot.Constants;
import frc.robot.Constants.CameraConstants;
import frc.robot.Constants.FieldConstants;
import frc.robot.Constants.SwerveConstants;
import frc.robot.utils.AllianceUtil;
import frc.robot.utils.LimelightTagsUpdate;
import monologue.Annotations.Log;
import monologue.Logged;

public class SwerveSubsystem extends SubsystemBase implements Logged {
  // The gyro sensor

  private final AHRS gyro;

  private SwerveDrivePoseEstimator swervePoseEstimator;

  private SwerveModule[] mSwerveMods;

  private Field2d m_field;

  private Pose2d simOdometryPose = new Pose2d();

  private boolean lookForNote;
  private double keepAngle = 0.0;
  private double timeSinceRot = 0.0;
  private double lastRotTime = 0.0;
  private double timeSinceDrive = 0.0;
  private double lastDriveTime = 0.0;
  @Log.NT(key = "actualstartpose")
  public Pose2d actualstartPose = new Pose2d();

  private double[] cancoderAtAbsoluteReset = new double[4];

  private static final Matrix<N3, N1> ODOMETRY_STDDEV = VecBuilder.fill(0.03, 0.03, Math.toRadians(1));
  private static final Matrix<N3, N1> VISION_STDDEV = VecBuilder.fill(0.5, 0.5, Math.toRadians(40));

  private final PIDController m_keepAnglePID =

      new PIDController(Constants.KeepAngle.kp, Constants.KeepAngle.ki, Constants.KeepAngle.kd);

  public PIDController m_alignNotePID = new PIDController(SwerveConstants.alignNoteKp, 0, SwerveConstants.alignNoteKd);

  private final Timer m_keepAngleTimer = new Timer();

  double poseDifferencefl = 0;
  double poseDifferencefr = 0;
  double poseDifference = 0;
  SwerveModuleState[] xLockStates = new SwerveModuleState[4];
  public SwerveModuleState[] wheelsAlignedStates = new SwerveModuleState[4];

  double xlim = Units.inchesToMeters(12);
  double ylim = Units.inchesToMeters(12);
  double deglim = Units.degreesToRadians(5);

  private boolean pathRunning;
  private boolean pathStarted;
  public PathPlannerPath currentPlannerPath;

  public double curretnpathstartTime;
  @Log.NT(key = "realstates")
  double[] rsbuff = new double[8];
  @Log.NT(key = "desiredstates")
  double[] desbuff = new double[8];

  public LimelightTagsUpdate flUpdate = new LimelightTagsUpdate(CameraConstants.frontLeftCamera, this);
  public LimelightTagsUpdate frUpdate = new LimelightTagsUpdate(CameraConstants.frontRightCamera, this);

  public boolean mod0connected;
  public boolean mod1connected;
  public boolean mod2connected;
  public boolean mod3connected;

  // Average will be taken over the last 5 samples
  LinearFilter speakerDistanceFilter = LinearFilter.movingAverage(10);// was 5
  LinearFilter stageDistanceFilter = LinearFilter.movingAverage(10);// was 5
  LinearFilter lobDistanceFilter = LinearFilter.movingAverage(10);// was 5
  LinearFilter noteDistanceFilter = LinearFilter.movingAverage(10);// was 5
  @Log.NT(key = "skidratio")
  private double skidRatio;

  BuiltInAccelerometer bui = new BuiltInAccelerometer();
  @Log.NT(key = "lastvxmps")
  private double lastvxmps;
  @Log.NT(key = "lastvymps")
  private double lastvymps;
  @Log.NT(key = "xaccpp")
  private double xaccpp;
  @Log.NT(key = "yaccpp")
  private double yaccpp;
  @Log.NT(key = "vxmps")
  private double vxmps;

  public SwerveSubsystem() {

    xLockStates[0] = new SwerveModuleState(0, Rotation2d.fromDegrees(45));
    xLockStates[1] = new SwerveModuleState(0, Rotation2d.fromDegrees(-45));
    xLockStates[2] = new SwerveModuleState(0, Rotation2d.fromDegrees(-45));
    xLockStates[3] = new SwerveModuleState(0, Rotation2d.fromDegrees(45));

    wheelsAlignedStates[0] = new SwerveModuleState(0, Rotation2d.fromDegrees(0));
    wheelsAlignedStates[1] = new SwerveModuleState(0, Rotation2d.fromDegrees(0));
    wheelsAlignedStates[2] = new SwerveModuleState(0, Rotation2d.fromDegrees(0));
    wheelsAlignedStates[3] = new SwerveModuleState(0, Rotation2d.fromDegrees(0));

    m_keepAngleTimer.reset();
    m_keepAngleTimer.start();
    m_keepAnglePID.enableContinuousInput(-Math.PI, Math.PI);

    gyro = new AHRS(SPI.Port.kMXP, (byte) 100);

    mSwerveMods = new SwerveModule[] {
        new SwerveModule(0, Constants.SwerveConstants.Mod0.constants),
        new SwerveModule(1, Constants.SwerveConstants.Mod1.constants),
        new SwerveModule(2, Constants.SwerveConstants.Mod2.constants),
        new SwerveModule(3, Constants.SwerveConstants.Mod3.constants)
    };

    swervePoseEstimator = new SwerveDrivePoseEstimator(
        Constants.SwerveConstants.swerveKinematics,
        getYaw(),
        getPositions(),
        new Pose2d(),
        ODOMETRY_STDDEV,
        VISION_STDDEV);

    simOdometryPose = swervePoseEstimator.getEstimatedPosition();

    m_field = new Field2d();

    SmartDashboard.putData("Field", m_field);

    absoluteResetFrontModuleEncoders();
    absoluteResetBackModuleEncoders();

    // Configure AutoBuilder
    AutoBuilder.configureHolonomic(
        this::getPose,
        this::resetPoseEstimator,
        this::getChassisSpeeds,
        this::driveRobotRelative,
        Constants.SwerveConstants.pathFollowerConfig,
        () -> {
          // Boolean supplier that controls when the path will be mirrored for the red
          // alliance
          // This will flip the path being followed to the red side of the field.
          // THE ORIGIN WILL REMAIN ON THE BLUE SIDE

          var alliance = DriverStation.getAlliance();
          if (alliance.isPresent()) {
            return alliance.get() == DriverStation.Alliance.Red;
          }
          return false;
        },
        this);

    // Set up custom logging to add the current path to a field 2d widget
    PathPlannerLogging.setLogActivePathCallback((poses) -> m_field.getObject("path").setPoses(poses));

    zeroGyro();

    resetPoseEstimator(new Pose2d());

    // setModuleDriveFF();now uses Constants instead
    // setModuleDriveKp();
    setModuleAngleKp();

    // SmartDashboard.putString("Drive/GyroVersion", gyro.getFirmwareVersion());
  }

  public void driveFieldRelative(ChassisSpeeds fieldRelativeSpeeds) {
    driveRobotRelative(ChassisSpeeds.fromFieldRelativeSpeeds(fieldRelativeSpeeds, getPose().getRotation()));
  }

  public void driveRobotRelative(ChassisSpeeds robotRelativeSpeeds) {
    ChassisSpeeds targetSpeeds = ChassisSpeeds.discretize(robotRelativeSpeeds, 0.02);
    SwerveModuleState[] targetStates = Constants.SwerveConstants.swerveKinematics.toSwerveModuleStates(targetSpeeds);
    setStates(targetStates);
    xaccpp = (robotRelativeSpeeds.vxMetersPerSecond - lastvxmps) / .02;
    lastvxmps = robotRelativeSpeeds.vxMetersPerSecond;
    yaccpp = (robotRelativeSpeeds.vyMetersPerSecond - lastvymps) / .02;
    lastvymps = robotRelativeSpeeds.vyMetersPerSecond;
  }

  public ChassisSpeeds getFieldRelativeSpeeds(double translation, double strafe, double rotation) {
    return ChassisSpeeds.fromFieldRelativeSpeeds(translation, strafe, rotation, getHeading());
  }

  public void drive(double translation, double strafe, double rotation, boolean fieldRelative, boolean isOpenLoop,
      boolean keepAngle) {
    if (keepAngle) {
      rotation = performKeepAngle(translation, strafe, rotation); // Calls the keep angle function to update the keep
                                                                  // angle or rotate
    }

    if (Math.abs(rotation) < 0.02) {
      rotation = 0.0;
    }

    SwerveModuleState[] swerveModuleStates = Constants.SwerveConstants.swerveKinematics.toSwerveModuleStates(
        ChassisSpeeds.discretize(
            fieldRelative
                ? ChassisSpeeds.fromFieldRelativeSpeeds(translation, strafe, rotation, getHeading())
                : new ChassisSpeeds(translation, strafe, rotation),
            .02));

    SwerveDriveKinematics.desaturateWheelSpeeds(swerveModuleStates, Constants.SwerveConstants.kmaxSpeed);

    for (SwerveModule mod : mSwerveMods) {
      mod.setDesiredState(swerveModuleStates[mod.moduleNumber], isOpenLoop);
    }
  }

  /* Used by SwerveControllerCommand in Auto */
  public void setStates(SwerveModuleState[] desiredStates) {
    SwerveDriveKinematics.desaturateWheelSpeeds(desiredStates, Constants.SwerveConstants.kmaxSpeed);
    for (SwerveModule mod : mSwerveMods) {
      mod.setDesiredState(desiredStates[mod.moduleNumber], false);
    }

  }

  /**
   * the method comes from 1690's
   * <a href="https://youtu.be/N6ogT5DjGOk?feature=shared&t=1674">online software
   * session</a>
   * gets the skidding ratio from the latest , that can be used to determine how
   * much the chassis is skidding
   * the skidding ratio is defined as the ratio between the maximum and minimum
   * magnitude of the "translational" part of the speed of the modules
   * 
   * @param swerveStatesMeasured  the swerve states measured from the modules
   * @param swerveDriveKinematics the kinematics
   * @return the skidding ratio, maximum/minimum, ranges from [1,INFINITY)
   */
  public static double getSkiddingRatio(SwerveModuleState[] swerveStatesMeasured,
      SwerveDriveKinematics swerveDriveKinematics) {
    final double angularVelocityOmegaMeasured = swerveDriveKinematics
        .toChassisSpeeds(swerveStatesMeasured).omegaRadiansPerSecond;
    final SwerveModuleState[] swerveStatesRotationalPart = swerveDriveKinematics
        .toSwerveModuleStates(new ChassisSpeeds(0, 0, angularVelocityOmegaMeasured));
    final double[] swerveStatesTranslationalPartMagnitudes = new double[swerveStatesMeasured.length];

    for (int i = 0; i < swerveStatesMeasured.length; i++) {
      final Translation2d swerveStateMeasuredAsVector = convertSwerveStateToVelocityVector(swerveStatesMeasured[i]),
          swerveStatesRotationalPartAsVector = convertSwerveStateToVelocityVector(swerveStatesRotationalPart[i]),
          swerveStatesTranslationalPartAsVector = swerveStateMeasuredAsVector.minus(swerveStatesRotationalPartAsVector);
      swerveStatesTranslationalPartMagnitudes[i] = swerveStatesTranslationalPartAsVector.getNorm();
      SmartDashboard.putNumber("Modules/ " + String.valueOf(i) + " transmag",
          swerveStatesTranslationalPartMagnitudes[i]);
    }

    double maximumTranslationalSpeed = 0, minimumTranslationalSpeed = Double.POSITIVE_INFINITY;
    for (double translationalSpeed : swerveStatesTranslationalPartMagnitudes) {
      maximumTranslationalSpeed = Math.max(maximumTranslationalSpeed, translationalSpeed);
      minimumTranslationalSpeed = Math.min(minimumTranslationalSpeed, translationalSpeed);
    }

    return maximumTranslationalSpeed / minimumTranslationalSpeed;
  }

  private static Translation2d convertSwerveStateToVelocityVector(SwerveModuleState swerveModuleState) {
    return new Translation2d(swerveModuleState.speedMetersPerSecond, swerveModuleState.angle);
  }

  /**
   * Sets the wheels into an X formation to prevent movement.
   */
  public void xLock() {
    setStates(xLockStates);
  }

  /**
   * Sets the wheels into an X formation to prevent movement.
   */
  public void wheelsAlign() {
    mSwerveMods[0].wheelAligning = true;
    mSwerveMods[1].wheelAligning = true;
    mSwerveMods[2].wheelAligning = true;
    mSwerveMods[3].wheelAligning = true;
    mSwerveMods[0].setDesiredState(wheelsAlignedStates[0], false);
    mSwerveMods[1].setDesiredState(wheelsAlignedStates[0], false);
    mSwerveMods[2].setDesiredState(wheelsAlignedStates[0], false);
    mSwerveMods[3].setDesiredState(wheelsAlignedStates[0], false);
    mSwerveMods[0].wheelAligning = false;
    mSwerveMods[1].wheelAligning = false;
    mSwerveMods[2].wheelAligning = false;
    mSwerveMods[3].wheelAligning = false;
  }

  public Command xLockCommand() {
    return Commands.runOnce(() -> xLock());
  }

  public void absoluteResetFrontModuleEncoders() {
    mSwerveMods[0].resetAngleToAbsolute();
    cancoderAtAbsoluteReset[0] = mSwerveMods[0].angleatabsolutereset;
    mSwerveMods[1].resetAngleToAbsolute();
    cancoderAtAbsoluteReset[1] = mSwerveMods[1].angleatabsolutereset;
  }

  public void absoluteResetBackModuleEncoders() {
    mSwerveMods[2].resetAngleToAbsolute();
    cancoderAtAbsoluteReset[2] = mSwerveMods[2].angleatabsolutereset;
    mSwerveMods[3].resetAngleToAbsolute();
    cancoderAtAbsoluteReset[3] = mSwerveMods[3].angleatabsolutereset;
  }

  public void resetAngleEncoders() {
    mSwerveMods[0].resetAngleEncoder(0);
    mSwerveMods[1].resetAngleEncoder(0);
    mSwerveMods[2].resetAngleEncoder(0);
    mSwerveMods[3].resetAngleEncoder(0);
  }

  public double getHeadingDegrees() {
    return Math.IEEEremainder((gyro.getAngle()), 360);
  }

  public double getAngle() {
    return gyro.getAngle();
  }

  @Log.NT(key = "poseestimate")
  public Pose2d getPose() {
    if (RobotBase.isReal())
      return swervePoseEstimator.getEstimatedPosition();
    else
      return simOdometryPose;
  }

  @Log.NT(key = "xdistance")
  public double getX() {
    return getPose().getX();
  }

  @Log.NT(key = "ydistance")
  public double getY() {
    return getPose().getY();
  }

  @Log.NT(key = "robdegrees")
  public double getAngleDegrees() {
    return getPose().getRotation().getDegrees();
  }

  public double getAngleRadians() {
    return getPose().getRotation().getRadians();
  }

  public double getModuleStickyFaults() {
    return mSwerveMods[0].getStickyFaults()
        + mSwerveMods[1].getStickyFaults()
        + mSwerveMods[2].getStickyFaults()
        + mSwerveMods[3].getStickyFaults();
  }

  public Command clearStickFaultsCommand() {
    return Commands.sequence(
        mSwerveMods[0].clearFaultsCommand(),
        mSwerveMods[1].clearFaultsCommand(),
        mSwerveMods[2].clearFaultsCommand(),
        mSwerveMods[3].clearFaultsCommand());
  }

  public void setModuleAngleKp() {
    mSwerveMods[0].setAngleKp();
    mSwerveMods[1].setAngleKp();
    mSwerveMods[2].setAngleKp();
    mSwerveMods[3].setAngleKp();
  }

  public double getDriveKp() {
    return mSwerveMods[0].getDriveKp();
  }

  public double getDriveFF() {
    return mSwerveMods[0].getDriveFF();
  }

  public Command setAngleKp() {
    return Commands.runOnce(() -> setModuleAngleKp());
  }

  public boolean driveIsBraked() {
    return mSwerveMods[0].driveIsBraked();
  }

  public boolean getIsRotating() {
    return gyro.isRotating();
  }

  public void setIdleMode(boolean brake) {
    mSwerveMods[0].setIdleMode(brake);
    mSwerveMods[1].setIdleMode(brake);
    mSwerveMods[2].setIdleMode(brake);
    mSwerveMods[3].setIdleMode(brake);
  }

  @Log.NT(key = "heading")
  public Rotation2d getHeading() {
    Rotation2d heading = new Rotation2d();
    if (DriverStation.getAlliance().isPresent() && DriverStation.getAlliance().get() == Alliance.Red)
      heading = getPose().getRotation().plus(new Rotation2d(Math.PI));
    else
      heading = getPose().getRotation();
    return heading;
  }

  public void resetPoseEstimator(Pose2d pose) {
    zeroGyro();
    swervePoseEstimator.resetPosition(getYaw(), getPositions(), pose);
    // gyro.setAngleAdjustment(pose.getRotation().getDegrees());
    simOdometryPose = pose;
  }

  public Command setPose(Pose2d pose) {
    return Commands.runOnce(() -> resetPoseEstimator(pose));
  }

  public SwerveDrivePoseEstimator getPoseEstimator() {
    return swervePoseEstimator;
  }

  public SwerveModulePosition[] getPositions() {
    SwerveModulePosition[] positions = new SwerveModulePosition[4];
    for (SwerveModule mod : mSwerveMods) {
      positions[mod.moduleNumber] = mod.getPosition();
    }
    return positions;
  }

  public double[] getDrivePositions() {
    return new double[] {
        mSwerveMods[0].getDrivePosition(),
        mSwerveMods[0].getDrivePosition(),
        mSwerveMods[0].getPositionRadians(),
        mSwerveMods[0].getPositionRadians()
    };
  }

  private boolean checkMod0CansOK() {
    return mSwerveMods[0].checkDriveMotorCanOK() && mSwerveMods[0].checkAngleMotorCanOK();
  }

  private boolean checkMod1CansOK() {
    return mSwerveMods[1].checkDriveMotorCanOK() && mSwerveMods[1].checkAngleMotorCanOK();
  }

  private boolean checkMod2CansOK() {
    return mSwerveMods[2].checkDriveMotorCanOK() && mSwerveMods[2].checkAngleMotorCanOK();
  }

  private boolean checkMod3CansOK() {
    return mSwerveMods[3].checkDriveMotorCanOK() && mSwerveMods[3].checkAngleMotorCanOK();
  }

  public Command testAllCan() {
    return Commands.sequence(
        Commands.runOnce(() -> mod0connected = false),
        runOnce(() -> mod1connected = false),
        runOnce(() -> mod2connected = false),
        runOnce(() -> mod3connected = false));
  }

  public SwerveModuleState[] getStates() {
    SwerveModuleState[] states = new SwerveModuleState[4];
    for (SwerveModule mod : mSwerveMods) {
      states[mod.moduleNumber] = mod.getState();
    }
    return states;
  }

  public void zeroGyro() {
    gyro.reset();
    updateKeepAngle();
  }

  @Log.NT(key = "yaw")
  public Rotation2d getYaw() {
    if (RobotBase.isReal())
      return gyro.getRotation2d();
    else
      return simOdometryPose.getRotation();
  }

  public double getR2dRads() {
    return gyro.getRotation2d().getRadians();
  }

  public float getPitch() {
    return gyro.getPitch();
  }

  public float getRoll() {
    return gyro.getRoll();
  }

  public double getGyroRate() {
    return gyro.getRate();
  }

  public Field2d getField() {
    return m_field;
  }

  @Log.NT(key = "chassisspeeds")
  public ChassisSpeeds getChassisSpeeds() {
    return Constants.SwerveConstants.swerveKinematics.toChassisSpeeds(getStates());
  }

  @Log.NT(key = "Field Relative Speeds")
  public ChassisSpeeds getFieldRelativeSpeeds() {
    return ChassisSpeeds.fromRobotRelativeSpeeds(
        getChassisSpeeds(), getHeading().plus(AllianceUtil.getZeroRotation()));
  }

  public void setLookForNote() {
    lookForNote = true;
  }

  public void resetLookForNote() {
    lookForNote = false;
  }

  public boolean getLookForNote() {
    return lookForNote;
  }

  @Override
  public void periodic() {

    if (!mod0connected) {
      mod0connected = checkMod0CansOK();
      SmartDashboard.putBoolean("Drive//Ok0ModCan", mod0connected);
    }
    if (!mod1connected) {
      mod1connected = checkMod1CansOK();
      SmartDashboard.putBoolean("Drive//Ok1ModCan", mod1connected);
    }
    if (!mod2connected) {
      mod2connected = checkMod2CansOK();
      SmartDashboard.putBoolean("Drive//Ok2ModCan", mod2connected);
    }
    if (!mod3connected) {
      mod3connected = checkMod3CansOK();
      SmartDashboard.putBoolean("Drive//Ok3ModCan", mod3connected);
    }

    if (mod0connected && mod1connected && mod2connected && mod3connected) {

      swervePoseEstimator.update(getYaw(), getPositions());

      getPose();

      putStates();

      flUpdate.execute();

      frUpdate.execute();

      skidRatio = getSkiddingRatio(getStates(), Constants.SwerveConstants.swerveKinematics);

      if (getPathRunning() && isStopped())
        resetPathRunning();

      if (getPathStarted() && !isStopped()) {
        setPathRunning();
        resetPathStarted();
      }

      SmartDashboard.putNumber("Drive//GyroYaw", getYaw().getDegrees());

      SmartDashboard.putNumber("Drive/SpeakerFeet", getDistanceFromSpeakerFt());

      SmartDashboard.putNumber("Drive/LobDistance", getDistanceFromLobTarget());
    }
  }

  public void setTargetPose(Pose2d pose) {
    targetPose = pose;
  }

  public Pose2d getStagePose() {
    return AllianceUtil.getAlliancePose(FieldConstants.stageBlueAlliance);
  }

  @Log.NT(key = "spkrDistMtrs")
  public double getDistanceFromSpeaker() {
    return round2dp(speakerDistanceFilter.calculate(AllianceUtil.getSpeakerPose().getTranslation()
        .getDistance(getPose().getTranslation())), 2);
  }

  public double getDistanceFromSpeakerFt() {
    return round2dp(Units.metersToFeet(getDistanceFromSpeaker()), 1);
  }

  @Log.NT(key = "lobDistMtrs")
  public double getDistanceFromLobTarget() {
    return round2dp(lobDistanceFilter.calculate(AllianceUtil.getLobPose().getTranslation()
        .getDistance(getPose().getTranslation())), 2);
  }

  @Log.NT(key = "stageOuterDistMtrs")
  public double getDistanceFromStageEdge() {
    return round2dp(
        stageDistanceFilter.calculate(AllianceUtil.getAlliancePose(FieldConstants.stageBlueAlliance).getTranslation()
            .getDistance(getPose().getTranslation())) - FieldConstants.stageRadius,
        2);
  }

  public double getDistanceFromVirtual() {
    return round2dp(virtualPose.getTranslation()
        .getDistance(getPose().getTranslation()), 2);
  }

  public double getDistanceFromNote(int number) {
    return round2dp(noteDistanceFilter.calculate(FieldConstants.centerNotes[number].getTranslation()
        .getDistance(getPose().getTranslation())), 2);
  }

  public double getAngleRadsToTarget() {
    double XDiff = targetPose.getX() - getX();
    double YDiff = targetPose.getY() - getY();
    return Math.atan2(YDiff, XDiff);

  }

  @Log.NT(key = "currentangletotarget")
  public double getAngleDegreesToTarget() {
    double XDiff = targetPose.getX() - getX();
    double YDiff = targetPose.getY() - getY();
    return Units.radiansToDegrees(Math.atan2(YDiff, XDiff));

  }

  /**
   * Keep angle function is performfz to combat drivetrain drift without the need
   * of constant "micro-adjustments" from the driver.
   * A PIDController is used to attempt to maintain the robot heading to the
   * keepAngle value. This value is updated when the robot
   * is rotated manually by the driver input
   * 
   * @return rotation command in radians/s
   * @param xSpeed is the input drive X speed command
   * @param ySpeed is the input drive Y speed command
   * @param rot    is the input drive rotation speed command
   */
  private double performKeepAngle(double xSpeed, double ySpeed, double rot) {
    double output = rot; // Output shouold be set to the input rot command unless the Keep Angle PID is
                         // called
    if (Math.abs(rot) >= 0.01) { // If the driver commands the robot to rotate set the
                                 // last rotate time to the current time
      lastRotTime = m_keepAngleTimer.get();
    }
    if (Math.abs(xSpeed) >= 0.01
        || Math.abs(ySpeed) >= 0.01) { // if driver commands robot to translate set the
                                       // last drive time to the current time
      lastDriveTime = m_keepAngleTimer.get();
    }
    timeSinceRot = m_keepAngleTimer.get() - lastRotTime; // update variable to the current time - the last rotate time

    timeSinceDrive = m_keepAngleTimer.get() - lastDriveTime; // update variable to the current time - the last drive
                                                             // time
    if (timeSinceRot < 0.25) { // Update keepAngle until 0.5s after rotate command stops to allow rotation
                               // move to finish

      keepAngle = getYaw().getRadians();

    } else if (Math.abs(rot) <= 0.01 && timeSinceDrive < 0.25) { // Run Keep angle pid
                                                                 // until 0.75s after drive
                                                                 // command stops to combat
                                                                 // decel drift
      output = m_keepAnglePID.calculate(getYaw().getRadians(), keepAngle); // Set output command to the result of the
                                                                           // Keep Angle PID
    }
    return output;
  }

  public Command setTurnKp() {
    return Commands.parallel(
        Commands.runOnce(() -> mSwerveMods[0].tuneAngleKp()),
        Commands.runOnce(() -> mSwerveMods[1].tuneAngleKp()),
        Commands.runOnce(() -> mSwerveMods[2].tuneAngleKp()),
        Commands.runOnce(() -> mSwerveMods[3].tuneAngleKp()));
  }

  public void updateKeepAngle() {
    keepAngle = getYaw().getRadians();
  }

  private void resetAll() {
    resetAngleEncoders();
    swervePoseEstimator.resetPosition(new Rotation2d(Math.PI), getPositions(), new Pose2d());
    simOdometryPose = new Pose2d();
    updateKeepAngle();
  }

  @Log.NT(key = "builtinaccelx")
  public double getBuiltInAccelX() {
    return bui.getX();
  }

  @Log.NT(key = "builtinaccely")
  public double getBuiltInAccelY() {
    return bui.getY();
  }

  @Log.NT(key = "navxaccelx")
  public float getNavXAccelX() {
    return gyro.getWorldLinearAccelX();
  }

  @Log.NT(key = "navxaccely")
  public float getNavXAccelY() {
    return gyro.getWorldLinearAccelY();
  }

  public Command setPoseToX0Y0() {
    return Commands.runOnce(() -> resetAll());
  }

  @Override
  public void simulationPeriodic() {

    m_field.setRobotPose(getPose());

    SwerveModuleState[] measuredStates

        = new SwerveModuleState[] {
            mSwerveMods[0].getState(),
            mSwerveMods[1].getState(),
            mSwerveMods[2].getState(),
            mSwerveMods[3].getState()
        };

    ChassisSpeeds speeds = Constants.SwerveConstants.swerveKinematics.toChassisSpeeds(measuredStates);

    simOdometryPose = simOdometryPose.exp(
        new Twist2d(
            speeds.vxMetersPerSecond * .02,
            speeds.vyMetersPerSecond * .02,
            speeds.omegaRadiansPerSecond * .02));

  }

  private void putStates() {

    double[] realStates = {
        round2dp(mSwerveMods[0].getState().angle.getDegrees(), 2),
        round2dp(mSwerveMods[0].getState().speedMetersPerSecond, 2),
        round2dp(mSwerveMods[1].getState().angle.getDegrees(), 2),
        round2dp(mSwerveMods[1].getState().speedMetersPerSecond, 2),
        round2dp(mSwerveMods[2].getState().angle.getDegrees(), 2),
        round2dp(mSwerveMods[2].getState().speedMetersPerSecond, 2),
        round2dp(mSwerveMods[3].getState().angle.getDegrees(), 2),
        round2dp(mSwerveMods[3].getState().speedMetersPerSecond, 2)
    };

    double[] theoreticalStates = {
        mSwerveMods[0].getDesiredState().angle.getDegrees(),
        mSwerveMods[0].getDesiredState().speedMetersPerSecond,
        mSwerveMods[1].getDesiredState().angle.getDegrees(),
        mSwerveMods[1].getDesiredState().speedMetersPerSecond,
        mSwerveMods[2].getDesiredState().angle.getDegrees(),
        mSwerveMods[2].getDesiredState().speedMetersPerSecond,
        mSwerveMods[3].getDesiredState().angle.getDegrees(),
        mSwerveMods[3].getDesiredState().speedMetersPerSecond
    };

    rsbuff = realStates;
    desbuff = theoreticalStates;

    SmartDashboard.putNumber("Drive//KeepAngle", keepAngle);
  }

  public static double round2dp(double number, int dp) {
    double temp = Math.pow(10, dp);
    double temp1 = Math.round(number * temp);
    return temp1 / temp;
  }

  @Log.NT(key = "isstopped")
  public boolean isStopped() {
    return mSwerveMods[0].isStopped()
        && mSwerveMods[1].isStopped()
        && mSwerveMods[2].isStopped()
        && mSwerveMods[3].isStopped();
  }

  public Command clearFaultsCommand() {
    return Commands.parallel(
        mSwerveMods[0].clearFaultsCommand(),
        mSwerveMods[1].clearFaultsCommand(),
        mSwerveMods[2].clearFaultsCommand(),
        mSwerveMods[3].clearFaultsCommand());
  }

  private final SysIdRoutine sysIdRoutine = new SysIdRoutine(
      new SysIdRoutine.Config(Volts.per(Second).of(1.0), Volts.of(4.0), null, null),
      new SysIdRoutine.Mechanism(
          (volts) -> {
            mSwerveMods[0].setCharacterizationVolts(volts.in(Volts));
            mSwerveMods[1].setCharacterizationVolts(volts.in(Volts));
            mSwerveMods[2].setCharacterizationVolts(volts.in(Volts));
            mSwerveMods[3].setCharacterizationVolts(volts.in(Volts));
          },
          log -> {
            log.motor("Front Left")
                .linearVelocity(MetersPerSecond.of(mSwerveMods[0].getDriveVelocity()))
                .linearPosition(Meters.of(mSwerveMods[0].getPosition().distanceMeters))
                .voltage(Volts.of(mSwerveMods[0].getVoltage()));

            log.motor("Front Right")
                .linearVelocity(MetersPerSecond.of(mSwerveMods[1].getDriveVelocity()))
                .linearPosition(Meters.of(mSwerveMods[1].getPosition().distanceMeters))
                .voltage(Volts.of(mSwerveMods[1].getVoltage()));

            log.motor("Back Left")
                .linearVelocity(MetersPerSecond.of(mSwerveMods[2].getDriveVelocity()))
                .linearPosition(Meters.of(mSwerveMods[2].getPosition().distanceMeters))
                .voltage(Volts.of(mSwerveMods[2].getVoltage()));

            log.motor("Back Right")
                .linearVelocity(MetersPerSecond.of(mSwerveMods[3].getDriveVelocity()))
                .linearPosition(Meters.of(mSwerveMods[3].getPosition().distanceMeters))
                .voltage(Volts.of(mSwerveMods[3].getVoltage()));
          },
          this));

  public boolean checkNote;

  public double currentpathstartTime;

  @Log.NT(key = "alignedtotarget")
  public boolean alignedToTarget;
  @Log.NT(key = "targetpose")
  public Pose2d targetPose = new Pose2d();

  public Pose2d virtualPose = new Pose2d();

  public Pose2d stagePose = new Pose2d();

  public Pose2d poseWhenShooting = new Pose2d();
  @Log.NT(key = "distancetopickup")
  public double distanceToPickup = 0;

  public boolean sourceActive;

  public boolean ampActive;
  @Log.NT(key = "noteseen")
  public boolean noteSeen;

  public double targetdistance;

  public boolean inhibitVision;

  private Pose2d pathfindpose = new Pose2d();

  public boolean noteposecreated;

  public boolean notePoseCalculated;

  public int targetNote;

  public double distanceLimelightToEstimator;
  @Log.NT(key = "aligning")
  public boolean aligning;

  public void setPathRunning() {
    pathRunning = true;
  }

  public void resetPathRunning() {
    pathRunning = false;
  }

  public boolean getPathRunning() {
    return pathRunning;
  }

  public void setPathStarted() {
    pathStarted = true;
  }

  public void resetPathStarted() {
    pathStarted = false;
  }

  public Pose2d getPathfindPose() {
    return pathfindpose;
  }

  public void setPathfindPose(Pose2d pose) {
    pathfindpose = pose;
  }

  public boolean getPathStarted() {
    return pathStarted;
  }

  public Command driveToPose(Pose2d pose) {
    return AutoBuilder.pathfindToPose(pose,
        SwerveConstants.pickUpConstraints);
  }

  public Command quasistaticForward() {
    return Commands.sequence(
        runOnce(
            () -> {
              mSwerveMods[0].setCharacterizationVolts(0.0);
              mSwerveMods[1].setCharacterizationVolts(0.0);
              mSwerveMods[2].setCharacterizationVolts(0.0);
              mSwerveMods[3].setCharacterizationVolts(0.0);
            }),
        Commands.waitSeconds(0.50),
        sysIdRoutine.quasistatic(Direction.kForward))
        .finallyDo(
            () -> {
              mSwerveMods[0].stopCharacterizing();
              mSwerveMods[1].stopCharacterizing();
              mSwerveMods[2].stopCharacterizing();
              mSwerveMods[3].stopCharacterizing();
            });
  }

  public Command quasistaticBackward() {
    return Commands.sequence(
        runOnce(
            () -> {
              mSwerveMods[0].setCharacterizationVolts(0.0);
              mSwerveMods[1].setCharacterizationVolts(0.0);
              mSwerveMods[2].setCharacterizationVolts(0.0);
              mSwerveMods[3].setCharacterizationVolts(0.0);
            }),
        Commands.waitSeconds(0.50),
        sysIdRoutine.quasistatic(Direction.kReverse))
        .finallyDo(
            () -> {
              mSwerveMods[0].stopCharacterizing();
              mSwerveMods[1].stopCharacterizing();
              mSwerveMods[2].stopCharacterizing();
              mSwerveMods[3].stopCharacterizing();
            });
  }

  public Command dynamicForward() {
    return Commands.sequence(
        runOnce(
            () -> {
              mSwerveMods[0].setCharacterizationVolts(0.0);
              mSwerveMods[1].setCharacterizationVolts(0.0);
              mSwerveMods[2].setCharacterizationVolts(0.0);
              mSwerveMods[3].setCharacterizationVolts(0.0);
            }),
        Commands.waitSeconds(0.50),
        sysIdRoutine.dynamic(Direction.kForward))
        .finallyDo(
            () -> {
              mSwerveMods[0].stopCharacterizing();
              mSwerveMods[1].stopCharacterizing();
              mSwerveMods[2].stopCharacterizing();
              mSwerveMods[3].stopCharacterizing();
            });
  }

  public Command dynamicBackward() {
    return Commands.sequence(
        runOnce(
            () -> {
              mSwerveMods[0].setCharacterizationVolts(0.0);
              mSwerveMods[1].setCharacterizationVolts(0.0);
              mSwerveMods[2].setCharacterizationVolts(0.0);
              mSwerveMods[3].setCharacterizationVolts(0.0);
            }),
        Commands.waitSeconds(0.50),
        sysIdRoutine.dynamic(Direction.kReverse))
        .finallyDo(
            () -> {
              mSwerveMods[0].stopCharacterizing();
              mSwerveMods[1].stopCharacterizing();
              mSwerveMods[2].stopCharacterizing();
              mSwerveMods[3].stopCharacterizing();
            });
  }

  public double[] getWheelRadiusCharacterizationPosition() {
    return new double[] {
        mSwerveMods[0].getPositionRadians(),
        mSwerveMods[1].getPositionRadians(),
        mSwerveMods[2].getPositionRadians(),
        mSwerveMods[3].getPositionRadians()
    };
  }

}
