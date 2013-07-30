/*
 * The following class is used to retrieve asset GH5 files and copy them over to the directory that allows
 * PI to process them. 
 */

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.Scanner;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.io.FileUtils;

public class AssetRetriever 
{
	// Information input by the user
	private String asset;
	private String date;
	
	// Number of files to send to PI at one time
	private Integer throttleThreshold;
	
	// Object which allows us to track the data that we have received
	private AssetTracker tracker;
	// Date that should be sent to the tracker
	private Date trackDate;
		
	// Source and Destination Directories
	private File sourceFile;
	private File destinationFile;
	
	// Files which we will store configuration and tracking info
	private File confFile;
	private File startUpFile;
	private File appDir;
	
	// Overall status of the process
	public static String status;
	
	// Allows us to keep track of unprocessed gh5 files
	private Boolean gh5Exist;
	
	// Vims Date manipulation object
	private VimsDate vimDate;
	
	// File manipulation objects
	private File[] allPaths;
	private File[] destinationPaths;
	private Vector<File> assetPaths;
	
	// Email object
	private RemedyEmail remEmail;
	
	// Constructor to initialize the processing of files
	AssetRetriever(String asset, String date)
	{
		// Initialization
		this.asset = asset;
		this.date = date.replace('-', '/');
		assetPaths = new Vector<File>(50);
		vimDate = new VimsDate();
		remEmail = new RemedyEmail(asset);
		
		begin();
	}
	
	// Handles the start of the entire asset retrieval application
	private void begin()
	{
		// Check if the user's date is within the current year
		if(!vimDate.isCurrentYear(date))
		{
			return;
		}
		
		// Get the AppData Directory
		appDir = new File(System.getenv("AppData"));
	
		// Try to create a folder for storing config and tracking info
		if(!createAppFolder())
		{
			status = "An error has occurred in retrieving the asset";
			return;
		}
		
		// Get the configuration from the conf file
		if(!getConfiguration())
		{
			status = "The configuration file could not be created, or is not properly setup!";
			return;
		}
	
		// Get a list of the files in the source directory
		allPaths = sourceFile.listFiles();
		
		status = "";
		
		// Read what we have transferred already
		tracker.read();
		
		// Determine if the asset is properly formatted
		if(isWellFormatted())
		{
			// Try to import the asset
			importAsset();
			
			// Check if the status hasn't been updated
			if(status.isEmpty())
			{
				if(trackDate != null)
				{
					tracker.write(new Asset(asset,trackDate));
				}
				
				status = "The asset " + asset + " was successfully transferred!";
				
				// Send an email to remedy
				if(!remEmail.send())
				{
					status = "A remedy ticket was not able to be opened";
				}
			}
		}
		else
		{
			status = "Requested asset does not exist.";
		}
	}
	
	// This method begins the file importing if the asset files exist or
	// Informs the user if the asset cannot be found
	private void importAsset()
	{
		if(assetExists())
		{
			beginImport();
		}
		else if(status.isEmpty())
		{
			status = "Requested asset does not exist!";
		}
	}
	
	// Search for the asset files and add them to the asset vector
	// If none exist return false
	private boolean assetExists()
	{
		// Loop through all of the paths in the source file
		for(File path : allPaths)
		{
			// Check if the input asset corresponds to this file
			if(path.getName().contains(asset))
			{
				// Check that the file date is newer than the input date
				if(vimDate.isvalidAsset(path, date))
				{
					// Check that the date is newer than previously transferred data
					if(tracker.isNew(asset, vimDate.getDateFromSourcePath(path)))
					{
						// We now know that the file is what we're looking for
						// Save it to the vector and update the tracking date
						assetPaths.add(path);
						trackDate = vimDate.getAssetDate();
					}
					else
					{
						status = "Newer data for this asset already exists in PI";
					}
				}
			}
		}
		
		// Check if we found any valid files
		if(assetPaths.size() > 0)
		{
			return true;
		}
		else
		{
			return false;
		}
	}
	
	// Now that we have files we can begin to copy them over
	private void beginImport()
	{
		File curPath;
		
		// Check if there are already files waiting to be processed
		checkGH5Directory();
		
		// Copy each file over to the PI Input folder
		for(int i = 0; i < assetPaths.size(); i++)
		{
			// Get the path to the file being copied
			curPath = assetPaths.elementAt(i);
			
			// Determine if the current path is a file or directory
			if(curPath.isDirectory())
			{
				try 
				{
					// Copy the entire directory over
					FileUtils.copyDirectoryToDirectory(curPath, destinationFile);
				} 
				catch (IOException e) 
				{
					status = "An error has occurred in retrieving the asset";
					return;
				}
			}
			else
			{
				// Copy the file over
				try
				{
					FileUtils.copyFileToDirectory(curPath, destinationFile, true);
				} 
				catch (IOException e)
				{
					status = "An error has occurred in retrieving the asset";
					return;
				}
				
				// Check if there are 5 unprocessed files currently in the PI input folder
				// If so wait until they are processed to copy anymore
				if((i+1) % throttleThreshold == 0)
				{
					checkGH5Directory();
				}
			}	
		}
	}
	
	// This method searches the PI Input directory for unprocessed gh5 files
	// and holds off until they have been processed
	private void checkGH5Directory()
	{
		String thisPath;
		gh5Exist = true;
		
		// Keep running the loop while there are GH5 files in the directory
		while(gh5Exist)
		{	
			// Get an updated list of files
			destinationPaths = destinationFile.listFiles();
			
			// If no files exist, return
			if(destinationPaths.length == 0)
				return;
			
			// Check each file for GH5 unprocessed files
			for(File path : destinationPaths)
			{
				thisPath = path.toString();

				// If one exists we need to hold off otherwise we can import new files
				if(!thisPath.contains(".hist"))
					gh5Exist = true;
				else
					gh5Exist = false;
			}
			
			// Wait 2 seconds
			try
			{
				Thread.sleep(2000);
			} 
			catch (InterruptedException e)
			{
				status = "An error has occurred in retrieving the asset";
				return;
			}
		}
	}
	
	// Create a folder for saving configuration files
	private boolean createAppFolder()
	{
		boolean success;
		
		// File to be locared in the users App Data directory
		startUpFile = new File(appDir.toString() + "/AssetRetriever");
		
		// Check if the file exists and create it if it doesn't
		if(!startUpFile.exists())
		{
			success = startUpFile.mkdir();
			
			if(success)
			{
				// Seed the tracker with the startup file
				tracker = new AssetTracker(startUpFile);
			}
			
			return success;
		}
		else
		{
			// File already exists seed the tracker
			tracker = new AssetTracker(startUpFile);
			return true;
		}
	}
	
	// Create or read the configuration file
	private boolean getConfiguration()
	{
		// Lets place the config file in the App Data directory
		confFile = new File(startUpFile.toString() + "/Asset_Retriever.conf");
		PrintWriter out;
		Scanner read;
		
		// Create the default config file if it does not exist
		if (!confFile.exists()) 
		{
			try
			{	
				confFile.createNewFile();
				
				// Create a demo config file
				out = new PrintWriter(new BufferedWriter(new FileWriter(confFile.getAbsoluteFile())));
				out.println("Source File Path goes Here");
				out.println("Destination File Path goes Here");
				out.println("5");
				out.close();
			} 
			catch (IOException e)
			{
				// Handle errors
				return false;
			}
			
			return false;
		}
		
		try
		{
			// Try and read the file 
			read = new Scanner (confFile);
				
			while(read.hasNext())
			{
				// Get the source and destination files along with the throttle value from config
				sourceFile = new File(read.nextLine());
				destinationFile = new File(read.nextLine());
				throttleThreshold = read.nextInt();
			}
			read.close();
			
			if(!sourceFile.exists() || !destinationFile.exists())
				return false;
				
		} 
		catch (FileNotFoundException e)
		{
			// Handle errors
			return false;
		}
		
		return true;
	}
	
	// Simple regular expression to determine if the asset is of the correct format
	private boolean isWellFormatted()
	{
		// Only allow strings of capital letters and digits with a total length of 8
		String assetRegex = "^[A-Z0-9]{8}";
		String dateRegex = "\\d{4}/\\d{2}/\\d{2}";
		Pattern pattern;
		Matcher matcher;
		boolean validAsset;
		
		// Check both the asset and date against the regular expressions defined above
		pattern = Pattern.compile(assetRegex);
		matcher = pattern.matcher(asset);
		
		validAsset = matcher.matches();
		
		pattern = Pattern.compile(dateRegex);
		matcher = pattern.matcher(date);
		
		return validAsset && matcher.matches();
	}
	
	// Getter method for the status value
	protected String getStatus()
	{
		return status;
	}
}
