# Practical Predictive Race Detection
This Artifact provides instructions to build and execute our implementation of SmartTrack 
of the OOPSLA 2019 Submission #284 Practical Predictive Race Detection.

This document details the following:
- Running the provided VM image
- Evaluation of running experiments corresponding to the results in the paper
- Testing new experiments with provided examples
- Notes on source code

SmartTrack tool represents our contribution's application of the epoch and ownership optimization (FTO) and the CCS optimization (SmartTrack) to Unoptimized predictive analyses WCP, DC, and the newly introduced WDC.
SmartTrack-optimized predictive analyses achieve performance competitive with widely used HB analysis that reports hard-to-detect races the HB analysis cannot find. By offering different coverage-soundness tradeoffs, SmartTrack-based analyses can leverage reporting new predictive races and better performance for detecting few, if any, false races in practice suggesting the potential for predictive analysis to be the prevailing approach for detecting data races, alternative to HB analysis.

Refer to the accompanying paper for more details: 
Practical Predictive Race Detection

### Important Information: This Artifact's VM image is packaged with 64GB RAM and 8 processors as default recommended settings.

# Running the VM Image
The Artifact is packaged as a GNU/Linux virtual machine image intended for VMware Player [https://www.vmware.com/]

1. Install VMware Player version 14.1.7 (though any minor version of major version 14 should work)
   - VMware Workstation Player Download Product link can be found here: https://my.vmware.com/web/vmware/downloads
   - For Linux:
	 - Save the VMware-Player-*.bundle
	 - Navigate to the directory with the bundle and change the permission to make the file executable.
		```
	    $ chmod a+x VMware-Player-*.bundle
		```
	 - Execute the file, usually with root privileges, but that is not always the case.
		```
	    $ sudo ./VMware-Player-*.bundle
		```
	 - Follow the prompts to install VMware Player. No need to enter a product key.

2. Unzip 'SmartTrack_Artifact.zip' which provides 'SmartTrack_Artifact.vmx' file.

3. Launch VMware Player and choose to 'Open a Virtual Machine.' 
   - Navigate to and select the 'SmartTrack_Artifact.vmx' file and select "I Copied It" if prompted
   - Opening the VM Image may take some time
   - Download VMware Tools for Linux if prompted

4. (Optional) The VM Image is packaged with 64GB RAM and 8 processors as default settings. 
   These settings are recommended to reproduce the results found in the paper correctly.
5. (Optional) If VMware Player is installed and SmartTrack_Artifact has been set up on a remote machine, run:
```
$ ssh -X username@remotemachine vmplayer
```
   If any issues manifest while trying to start VMware Player on a server, try running:
```
$ sudo vmware-modconfig --console --install-all
```

6. Log in using username [oopsla] and password [2019]
   - Open a terminal (black square on left toolbar)

#### Note: VMware Player limits the maximum Memory for a virtual machine to 64GB. This limitation prevents the h2 benchmark from adequately running for Unoptimized and FTO analyses, and therefore results for the h2 benchmark cannot accurately be reproduced.

# Executing Vindicator
The VM Image contains six essential components of the artifact:

1. '~/git/PIP-implementation': source code for a modified version of RoadRunner (https://github.com/stephenfreund/RoadRunner/releases/tag/v0.5) implementing SmartTrack and configurations Unopt w/G (Unoptimized DC and WDC with event graph G generation), Unopt w/o G (Unoptimized HB, WCP, DC, and WDC without event graph G generation), Epochs (FastTrack 2, based closely on RoadRunner's provided implementation of FastTrack 2), FTO (HB, WCP, DC, and WDC with epoch and ownership optimizations), and SmartTrack (WCP, DC, and WDC with epoch and ownership and CCS optimizations) as described in Table 1 in the paper.

2. '~/exp': EXP is a framework that provides facilities for executing experiments and generating results. Refer to the README file in the exp directory for more information.

3. '~/benchmarks/RoadRunnerBenchmarks': benchmark scripts and .jar for DaCapo benchmarks.

4. '~/exp-output': directory containing experimental output, initially including sample results.

5. '~/git/Parser': source code for parsing experimental results.

6. '~/workspace/generated-results': latex source for displaying parsed experimental results.

## Reproduce Results
#### Note: We changed the SmartTrack tool since submission and affected reproducing the results in the submitted paper in the following ways:
1. We changed the SmartTrack tool implementation from initializing "extra metadata" fields as empty maps to initializing "extra metadata" fields as null (delaying creating a map until it is needed). This change eliminates a performance anomaly between SmartTrack-based analyses and FTO-based analyses, actually improving the run-time and memory usage of SmartTrack-based analyses;
2. RoadRunner provides the -availableProcessors=<> parameter which limits the number of processors RoadRunner says the machine has. We changed setting this parameter from 8 to not setting the parameter (using the default value of the Runtime environment's available processors). By not setting this parameter the number of active threads will change during execution, affecting performance, characteristics, and race counts;
3. As mentioned in the paper, run-to-run variation and different performance characteristics, such as the execution's timing and memory access interleaving, naturally affect repeatability. The uninstrumented execution's wall-clock time and maximum resident set size directly affect the slowdown and memory usage factors for all configurations, but the comparison between analyses should remain reproducible.

************************
We use a tool called 'exp' to automate experiments and testing.

We include three scripts for three separate runs to reproduce results for both run-time and memory usage performance and run-time characteristics:
1. The script to reproduce FTO- and ST-analyses run-time and memory usage performance results (FTO- and ST- portions of Tables 3-7 and 8-11 for 95% confidence intervals in the paper):
```
$ ./exp/SmartTrackScript
```
2. The script to reproduce Unoptimized analyses run-time and memory usage performance results (Unopt- portions of Tables 3-7 and 8-11 for 95% confidence intervals in the paper):
```
$ ./exp/SmartTrackUnoptimizedScript
```
3. The script to reproduce run-time characteristics (Tables 2, 4, and 12 in the paper):
```
$ ./exp/SmartTrackCharacteristicsScript
```

#### Note: Our paper only provides run-time characteristics corresponding to ST-WDC analysis. However, collecting run-time characteristics for any configuration is possible. 

The scripts will run for a couple of hours. To run a single trial of an individual DaCapo benchmark as a customized experiment execute the following:
```
$ cd ~/exp
$ doexp-local --project=raptor --tasks=build,exp --workloadSize=small --timeout=600 --rrArgs=-Xmx50g,-Xloggc:/dev/stdout --config=rr_pip_fto_re_capo --bench=avrora9 --baseName=test_avrora --trial=1
```

Relevant flags include:
- -\-tasks=TASK: a comma-separated ordered list of tasks exp will run
Important tasks to run are:
  - build		[build tool configurations for RoadRunner]
  - exp		[generates and executes experiment commands]

- -\-workloadSize=small: only one workload size is available for RoadRunnerBenchmarks

- -\-timeout=SECONDS: the runtime limit in seconds for each trial (default=600 or 10 minutes)

- -\-rrArgs=RoadRunnerARGUMENTS: a comma-separated list of system arguments passed to RoadRunner
Imporant arguments to include are:
  - -Xmx__g			[sets the maximum heap size in GB allowed during execution. Recommended is 50GB or more]
  - -Xms__m			[sets the minimum heap size in MB during execution. Not recommended to use.]
  - -Xloggc:/dev/stdout	[enables Garbage Collection logging for exp to interpret]

- -\-config=CONFIG: a comma-separated list of configurations to run specific RoadRunner tools
Important configurations to run are:

| Configuration | Description |
| :---: | :---: |
| base_rrharness	| Uninstrumented Execution |
|||
| rr_dc_exc		| Unopt-DC w/ event graph G generation |
| rr_capo_exc		| Unopt-WDC w/ event graph G generation |
|||
| rr_hb			| Unopt-HB, default w/o event graph G generation |
| rr_hbwcp		| Unopt-WCP, default w/o event graph G generation |
| rr_dc_noG_exc		| Unopt-DC w/o event graph G generation |
| rr_capo_noG_exc	| Unopt-WDC w/o event graph G generation |
|||
| rr_pip_hb 		| FT2, our implementation of the FastTrack2 algorithm |
|||
| rr_pip_fto_hb      	| FTO-HB |
| rr_pip_fto_wcp	| FTO-WCP |
| rr_pip_fto_dc		| FTO-DC |
| rr_pip_fto_capo	| FTO-WDC |
|||
| rr_pip_fto_re_wcp	| ST-WCP |
| rr_pip_fto_re_dc	| ST-DC |
| rr_pip_fto_re_capo	| ST-WDC |

- -\-bench=BENCHMARK: a comma-separated list of benchmark names.
The following are the benchmark names found in the paper: [h29 exluded]

| | | | | |
| :---: | :---: | :---: | :---: | :---: |
| avrora9 | batik9 | jython9 | luindex9 | lusearch9-fixed |
| pmd9 | sunflow9 | tomcat9 | xalan9 |
|||||

- -\-baseName=DIR: name of directory that will contain generated result output under the '~/exp-output' directory.

- -\-trial=N: number of repetitions for each pair of benchmark + tool configuration
  
- -\-rrOptions=RoadRunnerOPTIONS: a comma-separated list of application arguments passed to the tool executed by RoadRunner
Important arguments to keep in mind are:
  - -countEvent			[enables collecting run-time characteristics. Only include option (-\-rrOptions=-countEvent) for enabling collection, collection is disabled otherwise.]
  - -availableProcessors=X	[sets X number of processors as available for running application]
Additional arguments are found by running:
```
$ cd ~/git/PIP-implementation ; ant ; source msetup
$ rrrun -help	[Relevant details found under * SmartTrack Tool Options *, * Unoptimized Configurations *, and * FTO and SmartTrack Configurations *]
```

-  -\-retryTrials=true -\-failedOnly=true: flags that will retry experiments for trials that failed to execute. 
   - -\-retryTrials=true: will retry failed trials automatically within the same run of exp
   - -\-failedOnly=true: will retry failed trials in a previous run of exp
#### Note: The DaCapo benchmark tomcat often fails the first trial attempt. Therefore, it is recommended to include --retryTrials=true when executing tomcat. Otherwise, include --failedOnly=true for any remaining runs necessary for any benchmark to complete all trials successfully.

Details on useful additional options are found by executing:
```
$ doexp-local --help
```

# Evaluating SmartTrack
The results from the executed experiments are displayed as a PDF containing performance and characteristic tables.

The '~/exp-output' directory is the output location for results collected by 'exp.'
Each experiment maintains a 'rerun' file under '~/exp-output/'baseName'/' that can be used to repeat the experiment. An experiment can be repeated with additional flags or setting existing flags differently.
For example:
```
$ ./exp-output/smarttrack_results/rerun --bench=avrora9
```
This command will rerun the experiments done for --baseName=smarttrack_results on only the DaCapo benchmark avrora.

## Generate Formatted Results

The Parser implementation parses the results from the executed experiments and generates a LaTeX file containing run-time and memory usage performance macros and run-time characteristics macros used to display the results as a PDF.
To parse results after running experiments, execute the following:
```
$ cd ~/git/Parser
$ java -classpath commons-math3-3.6.1/commons-math3-3.6.1.jar:bin parseSmartTrack <tool> <# of trials> <characteristics> <output_dir>
```
The arguments correspond to the executed experiment being parsed:

| Argument | Value | Description |
| :---: | :---: | :---: |
| \<tool> | Unopt or SmartTrack | Tool used in experiments. Unopt for unoptimized configurations. SmartTrack for FTO or ST configurations. |
| <# of trials> | Number of trials | Should match N of the --trial=N exp flag set for experiments. |
| \<characteristics> | true or false | true for parsing collected characteristics which requires --rrOptions=-countEvent to be set for experiements. false otherwise. |
| \<output_dir> | Directory name containing results | Should match DIR of the --baseName=DIR exp flag set for experiements. |

## View Formatted Results
The results from the executed experiments are displayed as a PDF.

The LaTeX project '~/workspace/generated-results' contains a PDF, called document.pdf, that shows all the tables featured in the paper. This PDF is used to compare the executed experiment's results to the results in the paper.

Once the results are parsed, a file named <output_dir>.tex containing LaTeX macros is generated in the '~/workspace/generated-results/result-macros' directory.

To view these generated results, '~/workspace/generated-results' needs to be rebuilt and the corresponding document.pdf under '~/workspace/generated-results' will be updated.
One method of rebuilding:
- In eclipse, right click on the 'generated-results' project and click on refresh.

# Running Existing and Custom Microbenchmarks

## Running Stand-Alone Examples

RoadRunner can build and execute tools on separate examples outside of exp.
To test out smaller examples, execute the following:
```
$ cd ~/git/PIP-implementation ; ant ; source msetup
$ javac test/Test.java
$ rrrun -noTidGC -pipCAPO -pipFTO -pipRE -tool=PIP test.Test
```

## Examples Corresponding to Figures from the Paper

The other examples in the ~/git/PIP-implementation/test' directory represent the figures in the paper, labeled Figure1 through Figure4d.
```
$ cd ~/git/PIP-implementation ; ant ; source msetup
$ javac test/Figure1.java
$ rrrun -noTidGC -pipCAPO -pipFTO -pipRE -tool=PIP test.Figure1
```
The following are important options :
#### Note: All options should come before '-tool='. 

| Options | Description |
| :---: | :---: |
| -noTidGC | Should always be included. Enforces tid for a thread that has completed is not reused. |
| -noxml | Disabled printing the sml summary at the end of the run. Can be excessive in some cases. |
|||
| -tool=WDC | Unoptimized configurations |
| -disableEventGraph | Disables event graph G generation |
|||
| -tool=PIP | FTO and ST configurations | 
| -pipFTO | Enables Epoch and Ownership optimization (FTO configuration) |
| -pipRE | Enables CCS optimizations |

#### Note: Use '-tool=WDC' with the following options:

| Options | Description |
| :---: | :---: |
| -dcDC | Unopt-DC w/ G |
| -dcCAPO | Unopt-WDC w/ G |
|||
| -dcHB -disableEventGraph | Unopt-HB w/o G |
| -dcWCP -disableEventGraph | Unopt-WCP w/o G |
| -dcDC -disableEventGraph | Unopt-DC w/o G |
| -dcCAPO -disableEventGraph | Unopt-WDC w/o G |

#### Note: Use '-tool=PIP' with the following options:

| Options | Description |
| :---: | :---: |
| -pipHB | FT2, our implementation of FastTrack 2 |
|||
| -pipHB -pipFTO | FTO-HB |
| -pipWCP -pipFTO | FTO-WCP |
| -pipDC -pipFTO | FTO-DC |
| -pipCAPO -pipFTO | FTO-WDC |
|||
| -pipWCP -pipFTO -pipRE | ST-WCP |
| -pipDC -pipFTO -pipRE | ST-DC |
| -pipCAPO -pipFTO -pipRE | ST-WDC |

## Creating and Running New Examples

The Test.java file under '~/git/PIP-implementation/test' is a template example.
Modify the Test.java file to evaluate simple examples or moderately complex examples.
Common operations and corresponding source code are:

| operation | code | Information |
| :---: | :---: | :---: |
| write(x) | x = 1; | assuming x is an integer variable |
| read(x) | int t = x; | for some thread local variable t |
| acquire(m) | synchronized(m) { | |
| release(m) | } | |
| sync(o) | sync(o); | is equivalent to executing acquire(o); read(oVar); write(oVar); release(o); |
| sleep(z) | sleepSec(z); | executing thread sleeps for z seconds. This allows for control over precise event execution order. |

For example, the following execution can be written as:

| execution operations | code |
| :---: | :---: |
| acquire(m) | synchronized(m) { |
| write(x) | x = 1; |
| release(m) | } |
| sync(o) | sync(o); |

To test out a new example after changes to test/Test.java, execute the following:
```
$ cd ~/git/PIP-implementation ; ant ; source msetup
$ javac test/Test.java
$ rrrun -noTidGC -pipWDC -pipFTO -pipRE -tool=PIP test.Test
```
Additional options for RoadRunner:
```
$ rrrun -help
```
#### Note: All dependencies and packages are already installed, but if an issue arises during building, then it is most likely related to a path or java version issue.

# Brief Guide to the Source Code
Our implementation is built in RoadRunner version 0.5. Use Eclipse to view and modify the source code. Please refer to https://github.com/stephenfreund/RoadRunner on how to set up RoadRunner in Eclipse.

Our tool, SmartTrack, and related configurations are located under two directories:
- '~/git/PIP-implementation/src/tools/pip' contains Epochs, FTO, and SmartTrack configurations (Table 1 in the paper); and
- '~/git/PIP-implementation/src/tools/wdc' contains Unoptimized configurations (Table 1 in the paper).
Important files are:

1. PIPTool.java file contains the source code implementing the FT2 HB analysis (Epochs of Table 1 in the paper), central FTO analyses (Algorithm 2 in the paper), and SmartTrack analyses (Algorithm 3 in the paper).
   - The boolean flags HB, WCP, DC, WDC (PIPTool.java, line 63-66) enables the HB, WCP, DC, WDC analysis, respectively.
   - The boolean flags FTO, RE (PIPTool.java, line 68-69) enables the FTO and SmartTrack (FTO+CS) optimizations to create FTO-{HB,WCP,DC,WDC} and ST-{HB,WCP,DC,WDC} configurations.
   - Running HB without setting FTO or RE flags produces results for FT2 HB analysis.
   - Running HB, WCP, DC, or WDC with FTO flag produces results for FTO-HB, FTO-WCP, FTO-DC, and FTO-WDC, respectively.
   - Running HB, WCP, DC, or WDC with FTO and RE flags produces results for ST-HB, ST-WCP, ST-DC, and ST-WDC, respectively.
  
The COUNT_EVENTS, COUNT_RACES, and PRINT_EVENTS flags in PIPTool.java will enable collecting run-time characteristics for evaluated programs. 
The VERBOSE and DEBUG flags in PIPTool.java will enable useful assertations and track additional information used during output. As a warning, these flags may cause slowdowns and are mostly used for testing the specific functionality of our tool.

2. WDCTool.java file contains the source code implementing the central vector clock analysis (Algorithm 1 in the paper).
   - The boolean flags HB, WCP, DC, WDC (WDCTool.java, line 108-111) enables the HB, WCP, DC, WDC configuration, respectively.
    The presence of the flag disables pieces of the Unoptimized analysis related to tracking the other relations to obtain a pure WCP analysis.
   - The boolean flag DISABLE_EVENT_GRAPH (WDCTool.java, line 119) enables the DC w/o G configuration.
    The presence of the flag disables pieces of the Vindicator analysis related to constructing the constraint graph during analysis. 

3. EventNode.java file contains the source code implementing the constraint graph construction and VindicateRace.

The VERBOSE flag in WDCTool.java and VERBOSE_GRAPH and USE_DEBUGGING_INFO flags in EventNode.java will enable useful assertions and track additional information used during output. As a warning, these flags may cause extreme slowdowns and are mostly used for testing the specific functionality of our tool.

