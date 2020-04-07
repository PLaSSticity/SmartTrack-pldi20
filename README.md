
This repository provides source code for the SmartTrack tool evaluated in our PLDI 2020 paper #228 SmartTrack: Efficient Predictive Race Detection.

SmartTrack tool represents our contribution's application of the epoch and ownership optimization (FTO) and the CCS optimization (SmartTrack) to Unoptimized predictive analyses WCP, DC, and the newly introduced WDC.
SmartTrack-optimized predictive analyses achieve performance competitive with widely used HB analysis that reports hard-to-detect races the HB analysis cannot find. By offering different coverage-soundness tradeoffs, SmartTrack-based analyses can leverage reporting new predictive races and better performance for detecting few, if any, false races in practice suggesting the potential for predictive analysis to be the prevailing approach for detecting data races, alternative to HB analysis.

Refer to the accompanying paper for more details: 
SmartTrack: Efficient Predictive Race Detection

## Setup

Clone repository and build source.

```
$ git clone https://github.com/PLaSSticity/SmartTrack-pldi20.git
$ cd ~/SmartTrack-pldi20
$ ant
$ source msetup
```

## Running SmartTrack

SmartTrack tool is built in RoadRunner, a dynamic analysis framework for concurrent Java programs.
To evaluate a \<benchmark>, execute:
```
$ rrrun <options> -tool=<analysis_type> <benchmark>
```

The following are important \<options> for specifying which analysis should evaluate the \<benchmark>.

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
| -dcDC | Unopt-DC w/ event graph G generation |
| -dcCAPO | Unopt-WDC w/ event graph G generation |
|||
| -dcHB -disableEventGraph | Unopt-HB w/o event graph G generation |
| -dcWCP -disableEventGraph | Unopt-WCP w/o event graph G generation |
| -dcDC -disableEventGraph | Unopt-DC w/o event graph G generation |
| -dcCAPO -disableEventGraph | Unopt-WDC w/o event graph G generation |

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


Additional options for RoadRunner:
```
$ rrrun -help
```

The Test.java file in the ~/SmartTrack-pldi20/test/ directory is a template example.
To illustrate SmartTrack-WDC analysis evaluating Test.java program, execute:
```
$ cd ~/SmartTrack-pldi20 ; ant ; source msetup
$ javac test/Test.java
$ rrrun -noTidGC -pipWDC -pipFTO -pipRE -tool=PIP test.Test
```

## Creating and Running New Examples

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
$ cd ~/SmartTrack-pldi20 ; ant ; source msetup
$ javac test/Test.java
$ rrrun -noTidGC -pipWDC -pipFTO -pipRE -tool=PIP test.Test
```

## Brief Guide to the Source Code

Our implementation is built in RoadRunner version 0.5. Use Eclipse to view and modify the source code. Please refer to https://github.com/stephenfreund/RoadRunner on how to set up RoadRunner in Eclipse.

Our tool, SmartTrack, and related configurations are located under two directories:
- '~/SmartTrack-pldi20/src/tools/pip' contains Epochs, FTO, and SmartTrack configurations (Table 1 in the paper); and
- '~/SmartTrack-pldi20/src/tools/wdc' contains Unoptimized configurations (Table 1 in the paper).
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

## Evaluating Benchmarks in the Paper

The evaluation in #228 SmartTrack: Efficient Predictive Race Detection uses the DaCapo benchmarks, version 9.12-bach, that have been harnessed and modified to work with RoadRunner. 
The benchmarks can be cloned:
```
$ git clone https://github.com/stephenfreund/RoadRunnerBenchmarks.git
```

The benchmarks,
| | | | | |
| :---: | :---: | :---: | :---: | :---: |
| avrora | batik | jython | luindex | lusearch |
| pmd | sunflow | tomcat | xalan |
|||||

, have TEST scripts for evaluation with RoadRunner. The TEST scripts need to be modified to enable SmartTrack tools.


#### Note on Results compared to paper:
1. RoadRunner provides the -availableProcessors=<> parameter which limits the number of processors RoadRunner says the machine has. We changed setting this parameter from 8 to not setting the parameter (using the default value of the Runtime environment's available processors). By not setting this parameter the number of active threads will change during execution, affecting performance, characteristics, and race counts;
2. As mentioned in the paper, run-to-run variation and different performance characteristics, such as the execution's timing and memory access interleaving, naturally affect repeatability. The uninstrumented execution's wall-clock time and maximum resident set size directly affect the slowdown and memory usage factors for all configurations, but the comparison between analyses should remain reproducible.

************************
