/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package edu.ub.tbd.parser;

import edu.ub.tbd.beans.LogLineBean;
import edu.ub.tbd.constants.AppConstants;
import edu.ub.tbd.entity.App;
import edu.ub.tbd.entity.LogIdGenerator;
import edu.ub.tbd.entity.Sql_log;
import edu.ub.tbd.entity.Unparsed_log_lines;
import edu.ub.tbd.entity.User;
import edu.ub.tbd.service.PersistanceFileService;
import edu.ub.tbd.service.PersistanceService;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

/**
 *
 * @author sathish
 */
public class LogParser {

    private final String sourceDir;
    private final String fileExtension;
    private final String logFilePath;
    private final HashMap<String, String> appNameLookUpMap = new HashMap<>();
    
    private JSONParser parser = new JSONParser();
    
    //Cached data to be persisted during app shutdown
    private final HashMap<String, User> usersMap = new HashMap<>();
    private HashMap<String, App> appsMap = new HashMap<>();
    
    
    private final Pattern regx_userGUIDInFilePath; //Use find on this regx and get guid from group 1
    
    //All Persistance Services below. They must be closed during shutdown
    private final PersistanceService ps_UnparsedLogLines;
    private final PersistanceService ps_SqlLog;
    
    private String longestLine = null; //TODO: <Sankar> Remove in final release
    private int longestLineLength = 0; //TODO: <Sankar> Remove in final release

    public LogParser(String _sourceDir, String _fileExtension, String _logFilePath) throws Exception{
        this.sourceDir = _sourceDir;
        this.fileExtension = _fileExtension;
        this.logFilePath = _logFilePath;
        regx_userGUIDInFilePath = Pattern.compile(sourceDir + File.separator +
                "logcat" + File.separator + "(\\p{Alnum}+)" + File.separator);
        
        this.ps_UnparsedLogLines = new PersistanceFileService(AppConstants.DEST_FOLDER, 
                AppConstants.OUTPUT_FILE_VALUE_SEPERATOR, Unparsed_log_lines.class);
        
        this.ps_SqlLog = new PersistanceFileService(AppConstants.DEST_FOLDER, 
                AppConstants.OUTPUT_FILE_VALUE_SEPERATOR, Sql_log.class);
    }

    public void parseLogsAndWriteFile() throws Exception{
        ArrayList<String> logFilesToProcess = getLogFilesToProcessFromBaseGZ();
        int counter = 0;
        for (String filePath : logFilesToProcess) {
            System.out.println(filePath);
            counter += 1;
            //TODO: <Satish> remove this. For testing purpose I am just parsing two files.
//            if (counter < 2) {
                parseSingleLogFile(filePath);
//            }  
        }
    }
    
    /**
     * Reads the base GZip file and returns only the file paths of the Log GZ files in it
     * @return the log.GZ file locations to be processed
     */
    private ArrayList<String> getLogFilesToProcessFromBaseGZ() {
        ArrayList<String> filesList = new ArrayList<>();
        File[] files = new File(sourceDir).listFiles();
        iterateFolders(files, filesList);
        return filesList;
    }

    /**
     * Helper method used in {@link LogParser#getLogFilesToProcessFromBaseGZ() getLogFilesToProcessFromBaseGZ()}
     * @param files
     * @param filesList Output Object
     */
    private void iterateFolders(File[] files, ArrayList<String> filesList) {
        for (File file : files) {
            String name = file.getName();
            if (file.isDirectory()) {
                iterateFolders(file.listFiles(), filesList); // Calls same method again.
            } else if (name.endsWith(fileExtension) && file.getAbsolutePath().contains("SQLite-Query-PhoneLab")) {
                filesList.add(file.getAbsolutePath());
            }
        }
    }

    private void parseSingleLogFile(String _sourceFile) throws Exception{
        extractUser(_sourceFile);
        
        BufferedReader br = null;
        Writer writer = null;
        String logLine = null;
        int lineNumber = 0;

        try {
            
            GZIPInputStream gzip = new GZIPInputStream(new FileInputStream(_sourceFile));
            br = new BufferedReader(new InputStreamReader(gzip));
            
            writer = new BufferedWriter(new FileWriter(this.logFilePath, true));
            //System.out.println(sourceFile);
            
            while ((logLine = br.readLine()) != null) {
                lineNumber++;
                LogLineBean logLineBean = new LogLineBean(logLine.split(AppConstants.SRC_LOG_FILE_VALUE_SEPERATOR));
                
                //TODO: <Sankar> Check how WARN and other levels function
                if(!LogLevel.ERROR.equals(logLineBean.getLog_level())){ //ERROR log level has no JSON to process
                    String key = logLineBean.getUser_guid() + "_" + logLineBean.getProcess_id() + "_" + logLineBean.getThread_id();
                    JSONObject JSON_Obj = (JSONObject) parser.parse(logLineBean.getLog_msg());

                    switch ((String) JSON_Obj.get("Action")) {
                        case "APP_NAME":
                            String appName = (String) JSON_Obj.get("AppName");
                            this.appNameLookUpMap.put(key, appName);
                            if(!appsMap.containsKey(appName)){
                                appsMap.put(appName, new App(appName));
                            }
                            break;
                        default:
                            //This is a valid SQL log entry carrying line which needs to go to both sql_log & analytics
                            int log_id = LogIdGenerator.getNextId();
                            int user_id = usersMap.get(logLineBean.getUser_guid()).getUser_id();
                            int app_id = -1; //If no app_id found this will be -1
                            
                            if ((appName = this.appNameLookUpMap.get(key)) != null) {
                                // I have an app name
                                app_id = appsMap.get(appName).getApp_id();
                            }
                        
                            Sql_log sql_log = new Sql_log(log_id, user_id, app_id, 
                                    logLineBean.getLog_msg()); 
                            ps_SqlLog.write(sql_log);
                        
                            //TODO : <Sankar> Implement all the pausible ACTION and throw the Assertion for default
                            //throw new AssertionError();
                    }
                    parser.reset();
                } else {
                    extractUnparsableLine(_sourceFile, lineNumber, logLineBean.getLog_msg(), "LogLevel = " + logLineBean.getLog_level());
                }
                
            }
            
        } catch (ParseException e) {
            extractUnparsableLine(_sourceFile, lineNumber, logLine);
        } catch (ArrayIndexOutOfBoundsException e) {
            extractUnparsableLine(_sourceFile, lineNumber, logLine, "ArrayIndexOutOfBoundsException : Invalid number of columns in the Line");
        } 
        finally {
            writer.close();
            br.close();
        }
    }
    
    /**
     * Extracts the user info from the filePath provided and adds to {@link LogParser#usersMap usersMap}<br>
     * User info is present at {@link LogParser#sourceDir sourceDir}+File.separator+"logcat"+File.separator+<b>[user_guid]</b>+File.separator+...<p>
     * Example: <br>
     * _filePath = /.../logcat/<b>0ee9cead2e2a3a58a316dc27571476e8973ff944</b>/tag/SQLite-Query-PhoneLab/2015/03/22.out.gz <br>
     * 
     * @param _filePath absolute path of the log file being processed
     */
    private void extractUser(String _filePath){
        Matcher m = regx_userGUIDInFilePath.matcher(_filePath);
        if(m.find()){
            String user_guid = m.group(1);
            if(!usersMap.containsKey(user_guid)){
                usersMap.put(user_guid, new User(user_guid));
            }
        }
    }
    
    private void extractUnparsableLine(String _file_location, int _file_line_number, 
            String _raw_data,  String _exception_trace) throws Exception
    {
        
        Unparsed_log_lines unparsedLogLine = new Unparsed_log_lines(
                _file_location, _file_line_number, _raw_data, _exception_trace);
        
        ps_UnparsedLogLines.write(unparsedLogLine);
    }
    
    private void extractUnparsableLine(String _file_location, int _file_line_number, 
            String _raw_data) throws Exception
    {
        extractUnparsableLine(_file_location, _file_line_number, _raw_data, " ");
    }
    
    /**
     * This method will persist the cached data : <br> 
     * usersMap, unparsed_log_lines_list, App, User_app_process, 
     */
    public void persistCacheData() throws Exception{
        System.out.println("Persisting usersMap....");
        persistUserData();

        System.out.println("Persisting appsMap....");
        persistAppData();

    }
    
    private void persistUserData() throws Exception{
        
        ArrayList<User> sortedUsers = new ArrayList<>(usersMap.size());
        sortedUsers.addAll(usersMap.values());
        Collections.sort(sortedUsers);  //Sorting is good here to insert in the ordered manner. Not Mandatory
        
        PersistanceService ps = new PersistanceFileService(AppConstants.DEST_FOLDER, 
                AppConstants.OUTPUT_FILE_VALUE_SEPERATOR, User.class);
        
        for(User user : sortedUsers){
            ps.write(user);
        }
        ps.close();
    }
    
    private void persistAppData() throws Exception{
        
        ArrayList<App> sortedApps = new ArrayList<>(appsMap.size());
        sortedApps.addAll(appsMap.values());
        Collections.sort(sortedApps);   //Sorting is good here to insert in the ordered manner. Not Mandatory
        
        PersistanceService ps = new PersistanceFileService(AppConstants.DEST_FOLDER, 
                AppConstants.OUTPUT_FILE_VALUE_SEPERATOR, App.class);
        
        for(App app : sortedApps){
            ps.write(app);
        }
        ps.close();
    }
    
    public void shutDown() throws Exception{
        persistCacheData();
        ps_UnparsedLogLines.close();
        ps_SqlLog.close();
    }
    
    /*
     * ##############################################################################
     *   All the below are back-up methods to are TO-BE deleted when decided unfit
     * ##############################################################################
     */
    
    /**
     * @deprecated 
     * @param _sourceFile
     * @throws Exception 
     */
    private void findLongestLine(String _sourceFile) throws Exception{
        String logLine = null;
        
        GZIPInputStream gzip = new GZIPInputStream(new FileInputStream(_sourceFile));
        BufferedReader br = new BufferedReader(new InputStreamReader(gzip));

        while ((logLine = br.readLine()) != null) {
            int currLineLength = logLine.length();
            if(currLineLength > longestLineLength){
                longestLineLength = currLineLength;
                longestLine = logLine;
            }
        }
        br.close();
    }
    
}