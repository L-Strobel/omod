# Code Structure

## Core

Implementations of the stochastic models.

Central class: [OMOD](../src/main/kotlin/de/uniwuerzburg/omod/core/Omod.kt)

Important Interfaces:
- [AgentFactory](../src/main/kotlin/de/uniwuerzburg/omod/core/AgentFactory.kt): Creates the population of agents by assigning socio-demographic features, as well as home, work, and school locations.
- [DestinationFinder](../src/main/kotlin/de/uniwuerzburg/omod/core/DestinationFinder.kt): Handles destination choice
- [ActivityGenerator](../src/main/kotlin/de/uniwuerzburg/omod/core/ActivityGenerator.kt): Determines the type of activities an agent undertakes and their durations  
- [CarOwnership](../src/main/kotlin/de/uniwuerzburg/omod/core/CarOwnership.kt): Determines if an agent owns a car or not
- [ModeChoice](../src/main/kotlin/de/uniwuerzburg/omod/core/ModeChoice.kt): Determines mode choice

Implementations of each of these interfaces can be swapped inside the OMOD class.
For example, the OMOD class holds a DestinationFinder;
if you provide a new implementation of the DestinationFinder Interface and replace OMODs finder
with it, then destination choice will be made according to your new method across the simulation.

### Models

Includes most basic data structures that have no or only basic internal logic,
like enumerations and locations.

## IO

Handles IO.

## Routing

Includes a wrapper around GraphHopper and caching logic.

## Utils

Miscellaneous utilities.