package sample;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

/**
 * A statically used class that takes an array of cpp and header files and reads them;
 * This class parses for information about the classes's methods and what it includes.
 *
 * @author Axolotl Development Team
 */
public class FileParser {

    //Methods Field Declaration
    private ArrayList<Method> methods;
    //Dependencies Field Declaration
    private HashSet<Dependence> dependencies;
    //Parameters for a makefile to be generated with
    private TestFixture fixture;

    /**
     * Constructor for the FileParser class that initializes methods and dependencies instance variables
     */
    public FileParser() {
        methods = new ArrayList<>();
        dependencies = new HashSet<>();
        fixture = new TestFixture();
    }

    public ArrayList<Method> getMethods() {
        return methods;
    }

    public void setMethods(ArrayList<Method> methods) {
        this.methods = methods;
    }

    public HashSet<Dependence> getDependencies() {
        return dependencies;
    }

    public void setDependencies(HashSet<Dependence> dependencies) {
        this.dependencies = dependencies;
    }

    /**
     * Receives the files to be parsed and extracts the necessary information;
     * Currently prints that information to the console, but will ultimately pass the information to the file writers.
     *
     * @param projectFiles The array of files to be read.
     * Will ultimately return the makefiles and the unit test and test fixture files.
     * @throws IOException Thrown if an IOException was experienced by BufferedReader reading a passed file.
     */
    public void parseSourceFiles(File[] projectFiles) throws IOException {
        for (File cFile : projectFiles) {
            if (cFile.getName().endsWith(".cpp")) {
                Dependence dep = makeDependence(cFile);
                if (dep != null)
                    dependencies.add(dep);
            }
            else if (cFile.getName().endsWith(".h")) {
                Method[] met = makeMethods(cFile);
                if(met != null)
                    methods.addAll(Arrays.asList(met));
            }
            else {
                Main.LOGGER.warning("An unexpected file has been passed.");
                throw new IOException("An unexpected file has been passed.");
            }
        }

    }

    /**
     * Generates the necessary output files (makefile, unit tests, test fixtures) to the destination selected by the
     * user
     * @param destination
     */
    public void generateOutputFiles(File destination) {
        try {
            Main.LOGGER.info("MakeFile: " + MakeFileWriter.writeMakefile(dependencies, fixture, destination).getName() + " has been generated.");
            UnitTestWriter.setDestination(destination);
            UnitTestWriter.writeUnitTests(methods, fixture);
        } catch (IOException e) {
            e.printStackTrace();
            Main.LOGGER.severe("An error in generation has occurred\n" + e.toString());
        }

        consoleTestBecauseWeDontKnowHowToUseJUnitRightNow(methods, dependencies);
    }

    /**
     * Reads a c++ file and creates a list of all the project files and c++ libraries the class depends on.
     *
     * @param cppFile The c++ file to be read
     * @return The list of project files and c++ libraries that this class files depends on
     * @throws IOException If and I/O exception occurred when attempting to read the file
     */
    private static Dependence makeDependence(File cppFile) throws IOException {
        // Gets the string of the file name without its file type signifier
        String className = cppFile.getName().substring(0, cppFile.getName().indexOf('.'));
        HashSet<String> dependencies = new HashSet<>();
        HashSet<String> libraries = new HashSet<>();

        try (BufferedReader br = new BufferedReader(new FileReader(cppFile))) {
            String line = br.readLine();
            if(line == null) {
                Main.LOGGER.warning("Blank file read.");
                return null;
            }
            if(line.equals(UnitTestWriter.getUnitTestHeader())){
                Main.LOGGER.info("Unit test identified, Skipping. We don't go deep.");
                return null;
            }
            // Reads the whole file
            while (line != null) {
                // Cuts any line comments out of the considered line
                if (line.contains("//"))
                    line = line.substring(0, line.indexOf("//"));
                line = line.trim();
                /* Looks to see if the signifier "#include" heads the line;
                   If so, it reads the line;
                   If not, it ignores the line.
                 */
                if (line.startsWith("#include")) {
                    // Gets just the class name after the signifier "#includes"
                    String shortLine = line.substring(8).trim();
                    // Libraries should be enclosed in <> while project classes should be enclosed in ""
                    switch (shortLine.charAt(0)) {
                        case '<':
                            // Strips .h if it exists in the include deceleration
                            libraries.add(shortLine.substring(1, shortLine.indexOf('.') == -1 ?
                                    shortLine.indexOf('>') : shortLine.indexOf('.')));
                            break;
                        case '"':
                            // Strips .h if it exists in the include deceleration
                            dependencies.add(shortLine.substring(1, shortLine.indexOf('.') == -1 ?
                                    shortLine.lastIndexOf('"') : shortLine.indexOf('.')));
                            break;
                        default:
                            /* C++ #include signifier is very syntactically strict;
                               We can expect any compilable code to never cause this exception to be thrown.
                             */
                            throw new RuntimeException("The #include line does not appear to be formatted properly.");
                    }
                }
                // Reads the next line
                line = br.readLine();
            }
        } catch (FileNotFoundException e) {
            Main.LOGGER.severe("Somehow, a file that made its way into the FileParser was not found." +
                    "These files should have ultimately come from a browse functionality." +
                    "This should not be possible.");
            throw e;
        } catch (IOException e) {
            Main.LOGGER.warning("An IOException was caught processing: " + cppFile.getName() +
                    ". This occurred while parsing for methods.");
            throw e;
        }

        // Deals with empty sets, setting them to an array of size 1 with the empty string in it
        String[] depArray, libArray;
        if (dependencies.isEmpty()) {
            depArray = new String[1];
            depArray[0] = "";
        } else {
            depArray = new String[dependencies.size()];
            dependencies.toArray(depArray);
        }
        if (libraries.isEmpty()) {
            libArray = new String[1];
            libArray[0] = "";
        } else {
            libArray = new String[libraries.size()];
            libraries.toArray(libArray);
        }
        return new Dependence(className, depArray, libArray);
    }

    /**
     * Reads a C++ file and finds each method declared in the file, turning them into a Method object;
     * The methods must be specifically declared, with no body, for this method to be able to read them;
     * A header file is recommended, but results will be given with any file that declares methods.
     *
     * @param hFile The header file to be searched through;
     *              Will accept any file in actuality,
     *              However any file without a properly declared method will yield no results.
     * @return a list of the passed file's methods
     */
    private static Method[] makeMethods(File hFile) throws IOException {
        /* This regex represents methodReturnType methodName(paramType1 param1, paramType2 param2,...);
           The important thing is the parenthesis and the white spaces;
           Even though this regex could allow methods with improperly formatted parts,
           ie Stri[]ng or a method name with illegal characters,
           these uncompilable parts are not expected in the passes files.
         */
        String regex = "\\S+\\s+\\S+\\s*\\(\\s*(\\S+\\s+\\S+\\s*,?\\s*)*\\).*";
        ArrayList<Method> methods = new ArrayList<>();
        // Grabs the class name by taking every part before the file's type
        String className = hFile.getName().substring(0, hFile.getName().indexOf('.'));
        String currentReturnType, currentMethodName;
        String[] currentParamTypes;

        try (BufferedReader br = new BufferedReader(new FileReader(hFile))) {
            String line = br.readLine();
            if(line == null) {
                Main.LOGGER.warning("Blank file read.");
                return null;
            }
            if(line.equals(TestFixture.getTestFixtureHeader())) {
                Main.LOGGER.info("Test fixture detected, Skipping. We don't go deep.");
                return null;
            }
            String restOfLine = "";
            // Reads the whole file
            while (line != null) {
                // Cuts any line comments out of the considered line
                if (line.contains("//"))
                    line = line.substring(0, line.indexOf("//"));

                /* Extends the considered string to include the next line if an open parenthesis was not closed;
                   It is possible that, do to page space constraints,
                   the parameters were declared across different lines;
                   This part accounts for that possibility.
                 */
                while (line.contains("(") && !line.contains(")")) {
                    line += " " + br.readLine().trim();
                    if (line.contains("//"))
                        line = line.substring(0, line.indexOf("//"));
                }
                line = line.trim();
                /* Checks if the considered string mates the regex defined above;
                   If so, begins to break apart and store the information of the line;
                   If not, the line is ignored.
                 */
                if (line.matches(regex)) {
                    /* Gets the first piece of information separated by a space or tab;
                       This piece of information is the method's return type.
                     */
                    currentReturnType = line.substring(0, line.indexOf(' ') == -1 ?
                            line.indexOf('\t') : line.indexOf(' ')).trim();
                    // Trims down the considered string to removed the information recently stored
                    line = line.substring(line.indexOf(' ') == -1 ? line.indexOf('\t') : line.indexOf(' ')).trim();
                    /* Gets the next piece of information just before the open parenthesis, ignoring whitespaces;
                       This piece of information is the method's name.
                     */
                    currentMethodName = line.substring(0, line.indexOf('(')).trim();
                    /* Trims down the considered string to removed the information recently stored;
                       Also removes anything past the close parenthesis;
                       What we should have now is the list of method parameters.
                     */
                    restOfLine = line.substring(line.indexOf(')') + 1);
                    line = line.substring(line.indexOf('(') + 1, line.indexOf(')')).trim();
                    // Splits the method parameters around any commas and puts the pieces into an array
                    currentParamTypes = line.split(",");
                    for (int i = 0; i < currentParamTypes.length; i++) {
                        currentParamTypes[i] = currentParamTypes[i].trim();
                        /* Accounts for the possibility that there are no parameters;
                           If there are parameters,
                           represented by currentParamTypes[0] not containing the empty string,
                           the parameter type is separated from the parameter name by reading for a white space;
                           The parameter name is discarded.
                         */
                        if (!currentParamTypes[i].isEmpty())
                            currentParamTypes[i] = currentParamTypes[i].substring(0,
                                    currentParamTypes[i].indexOf(' ') == -1 ?
                                            currentParamTypes[i].indexOf('\t') :
                                            currentParamTypes[i].indexOf(' ')).trim();
                    }
                    methods.add(new Method(className, currentReturnType, currentMethodName, currentParamTypes));
                    if(!restOfLine.contains(";"))
                        curlyBurn(br, restOfLine);
                    restOfLine = "";
                }
                line = br.readLine();
            }
        } catch (FileNotFoundException e) {
            Main.LOGGER.severe("Somehow, a file that made its way into the FileParser was not found." +
                    "These files should have ultimately come from a browse functionality." +
                    "This should not be possible.");
            throw e;
        } catch (IOException e) {
            Main.LOGGER.warning("An IOException was caught processing: " + hFile.getName() +
                    ". This occurred while parsing for methods.");
            throw e;
        }
        Method[] methodsArray = new Method[methods.size()];
        methods.toArray(methodsArray);
        return methodsArray;
    }

    private static void curlyBurn(BufferedReader br, String restOfLine) throws IOException {
        int brace = 0;
        while(restOfLine != null) {
            if (restOfLine.contains("{")) {
                brace++;
                restOfLine = restOfLine.substring(restOfLine.indexOf('{') + 1);
            }
            if (restOfLine.contains("}")) {
                brace--;
                if(brace == 0)
                    return;
                restOfLine = restOfLine.substring(restOfLine.indexOf('}') + 1);
            }
            restOfLine = br.readLine();
        }
        if(brace != 0)
            throw new IllegalArgumentException("This file does not close a curly brace.");
    }

    /**
     * A temporary test method that prints all parsed information to the console;
     * Will ultimately be removed and its functionality will be covered by JUnit testing.
     *
     * @param methods      The collection of methods to be printed.
     * @param dependencies The collection of dependencies to be printed.
     */
    private static void consoleTestBecauseWeDontKnowHowToUseJUnitRightNow(ArrayList<Method> methods,
                                                                          HashSet<Dependence> dependencies) {
        System.out.println("Parsed Source File Methods and Dependencies");
        methods.forEach(n -> System.out.println(n.toString()));
        dependencies.forEach(n -> System.out.println(n.toString()));
        System.out.println();
    }

    public static String[][] parseCSVFile(File csv) {
        String[][] params;
        ArrayList<String[]> tempParams = new ArrayList<String[]>();
        try (BufferedReader br = new BufferedReader(new FileReader(csv))) {
            int numLines = 0;
            for(String currentLine = br.readLine(); currentLine != null; currentLine = br.readLine()){
                String[] currentParams = currentLine.split(",");
                tempParams.add(new String[currentParams.length]);
                for(int i = 0; i<currentParams.length; i++){
                    tempParams.get(numLines)[i] = currentParams[i];
                }
                numLines++;
            }

        } catch (java.io.IOException e) {
            Main.LOGGER.severe("Error when reading CSV file.");
        }

        return tempParams.toArray(new String[tempParams.size()][]);
    }


    /*
    Setter for the test fixture to be used. Invoke when you don't want to be using the default parameters for a test.
    @param fixture the TestFixture to be applied to test generation
     */
    public void updateTestFixture(TestFixture fixture){
        this.fixture = fixture;
    }
}