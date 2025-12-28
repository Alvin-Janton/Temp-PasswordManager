package managerV2;

import java.io.FileWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Scanner;

public class temp {

	public static void main(String[] args) throws IOException {
		int [] nums = {1,2,3,4,5,6,7,8,9};
		
		System.out.println(Arrays.asList(nums));
		
	}
	
	public static int countLines(String fileName) throws FileNotFoundException {
    Scanner scanner = new Scanner(new File(fileName));
		
		int lineCount = 0;
		
		while(scanner.hasNextLine()) {
			String line = scanner.nextLine();
			if(line.isBlank() || line.isEmpty()) {
				continue;
			}
			lineCount++;
		}
		scanner.close();
		return lineCount;
	}

}
