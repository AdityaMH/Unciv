package com.unciv.logic.map.tile

import com.unciv.Constants
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.StateForConditionals
import com.unciv.models.ruleset.unique.UniqueType


enum class ImprovementBuildingProblem {
    WrongCiv, MissingTech, Unbuildable, NotJustOutsideBorders, OutsideBorders, UnmetConditional, Obsolete, MissingResources, Other
}

class TileInfoImprovementFunctions(val tileInfo: TileInfo) {

    /** Returns true if the [improvement] can be built on this [TileInfo] */
    fun canBuildImprovement(improvement: TileImprovement, civInfo: CivilizationInfo): Boolean = getImprovementBuildingProblems(improvement, civInfo).none()

    /** Generates a sequence of reasons that prevent building given [improvement].
     *  If the sequence is empty, improvement can be built immediately.
     */
    fun getImprovementBuildingProblems(improvement: TileImprovement, civInfo: CivilizationInfo): Sequence<ImprovementBuildingProblem> = sequence {
        val stateForConditionals = StateForConditionals(civInfo, tile = tileInfo)

        if (improvement.uniqueTo != null && improvement.uniqueTo != civInfo.civName)
            yield(ImprovementBuildingProblem.WrongCiv)
        if (improvement.techRequired != null && !civInfo.tech.isResearched(improvement.techRequired!!))
            yield(ImprovementBuildingProblem.MissingTech)
        if (improvement.hasUnique(UniqueType.Unbuildable, stateForConditionals))
            yield(ImprovementBuildingProblem.Unbuildable)

        if (tileInfo.getOwner() != civInfo && !improvement.hasUnique(UniqueType.CanBuildOutsideBorders, stateForConditionals)) {
            if (!improvement.hasUnique(UniqueType.CanBuildJustOutsideBorders, stateForConditionals))
                yield(ImprovementBuildingProblem.OutsideBorders)
            else if (tileInfo.neighbors.none { it.getOwner() == civInfo })
                yield(ImprovementBuildingProblem.NotJustOutsideBorders)
        }

        if (improvement.getMatchingUniques(UniqueType.OnlyAvailableWhen, StateForConditionals.IgnoreConditionals).any {
                    !it.conditionalsApply(stateForConditionals)
                })
            yield(ImprovementBuildingProblem.UnmetConditional)

        if (improvement.getMatchingUniques(UniqueType.ObsoleteWith, stateForConditionals).any {
                    civInfo.tech.isResearched(it.params[0])
                })
            yield(ImprovementBuildingProblem.Obsolete)

        if (improvement.getMatchingUniques(UniqueType.ConsumesResources, stateForConditionals).any {
                    civInfo.getCivResourcesByName()[it.params[1]]!! < it.params[0].toInt()
                })
            yield(ImprovementBuildingProblem.MissingResources)

        val knownFeatureRemovals = tileInfo.ruleset.tileImprovements.values
            .filter { rulesetImprovement ->
                rulesetImprovement.name.startsWith(Constants.remove)
                        && RoadStatus.values().none { it.removeAction == rulesetImprovement.name }
                        && (rulesetImprovement.techRequired == null || civInfo.tech.isResearched(rulesetImprovement.techRequired!!))
            }

        if (!canImprovementBeBuiltHere(improvement, tileInfo.hasViewableResource(civInfo), knownFeatureRemovals, stateForConditionals))
        // There are way too many conditions in that functions, besides, they are not interesting
        // at least for the current usecases. Improve if really needed.
            yield(ImprovementBuildingProblem.Other)
    }

    /** Without regards to what CivInfo it is, a lot of the checks are just for the improvement on the tile.
     *  Doubles as a check for the map editor.
     */
    internal fun canImprovementBeBuiltHere(
        improvement: TileImprovement,
        resourceIsVisible: Boolean = tileInfo.resource != null,
        knownFeatureRemovals: List<TileImprovement>? = null,
        stateForConditionals: StateForConditionals = StateForConditionals(tile=tileInfo)
    ): Boolean {

        fun TileImprovement.canBeBuildOnThisUnbuildableTerrain(
            knownFeatureRemovals: List<TileImprovement>? = null,
        ): Boolean {
            val topTerrain = tileInfo.getLastTerrain()
            // We can build if we are specifically allowed to build on this terrain
            if (isAllowedOnFeature(topTerrain.name)) return true

            // Otherwise, we can if this improvement removes the top terrain
            if (!hasUnique(UniqueType.RemovesFeaturesIfBuilt, stateForConditionals)) return false
            val removeAction = tileInfo.ruleset.tileImprovements[Constants.remove + topTerrain.name] ?: return false
            // and we have the tech to remove that top terrain
            if (removeAction.techRequired != null && (knownFeatureRemovals == null || removeAction !in knownFeatureRemovals)) return false
            // and we can build it on the tile without the top terrain
            val clonedTile = tileInfo.clone()
            clonedTile.removeTerrainFeature(topTerrain.name)
            return clonedTile.improvementFunctions.canImprovementBeBuiltHere(improvement, resourceIsVisible, knownFeatureRemovals, stateForConditionals)
        }

        return when {
            improvement.name == tileInfo.improvement -> false
            tileInfo.isCityCenter() -> false

            // First we handle a few special improvements

            // Can only cancel if there is actually an improvement being built
            improvement.name == Constants.cancelImprovementOrder -> (tileInfo.improvementInProgress != null)
            // Can only remove roads if that road is actually there
            RoadStatus.values().any { it.removeAction == improvement.name } -> tileInfo.roadStatus.removeAction == improvement.name
            // Can only remove features if that feature is actually there
            improvement.name.startsWith(Constants.remove) -> tileInfo.terrainFeatures.any { it == improvement.name.removePrefix(
                Constants.remove) }
            // Can only build roads if on land and they are better than the current road
            RoadStatus.values().any { it.name == improvement.name } -> !tileInfo.isWater
                    && RoadStatus.valueOf(improvement.name) > tileInfo.roadStatus

            // Then we check if there is any reason to not allow this improvement to be build

            // Can't build if there is already an irremovable improvement here
            tileInfo.improvement != null && tileInfo.getTileImprovement()!!.hasUnique(UniqueType.Irremovable, stateForConditionals) -> false

            // Can't build if this terrain is unbuildable, except when we are specifically allowed to
            tileInfo.getLastTerrain().unbuildable && !improvement.canBeBuildOnThisUnbuildableTerrain(knownFeatureRemovals) -> false

            // Can't build if any terrain specifically prevents building this improvement
            tileInfo.getTerrainMatchingUniques(UniqueType.RestrictedBuildableImprovements, stateForConditionals).any {
                    unique -> !improvement.matchesFilter(unique.params[0])
            } -> false

            // Can't build if the improvement specifically prevents building on some present feature
            improvement.getMatchingUniques(UniqueType.CannotBuildOnTile, stateForConditionals).any {
                    unique -> tileInfo.matchesTerrainFilter(unique.params[0])
            } -> false

            // Can't build if an improvement is only allowed to be built on specific tiles and this is not one of them
            // If multiple uniques of this type exists, we want all to match (e.g. Hill _and_ Forest would be meaningful)
            improvement.getMatchingUniques(UniqueType.CanOnlyBeBuiltOnTile, stateForConditionals).let {
                it.any() && it.any { unique -> !tileInfo.matchesTerrainFilter(unique.params[0]) }
            } -> false

            // Can't build if the improvement requires an adjacent terrain that is not present
            improvement.getMatchingUniques(UniqueType.MustBeNextTo, stateForConditionals).any {
                !tileInfo.isAdjacentTo(it.params[0])
            } -> false

            // Can't build it if it is only allowed to improve resources and it doesn't improve this resource
            improvement.hasUnique(UniqueType.CanOnlyImproveResource, stateForConditionals) && (
                    !resourceIsVisible || !tileInfo.tileResource.isImprovedBy(improvement.name)
                    ) -> false

            // At this point we know this is a normal improvement and that there is no reason not to allow it to be built.

            // Lastly we check if the improvement may be built on this terrain or resource
            improvement.canBeBuiltOn(tileInfo.getLastTerrain().name) -> true
            tileInfo.isLand && improvement.canBeBuiltOn("Land") -> true
            tileInfo.isWater && improvement.canBeBuiltOn("Water") -> true
            // DO NOT reverse this &&. isAdjacentToFreshwater() is a lazy which calls a function, and reversing it breaks the tests.
            improvement.hasUnique(UniqueType.ImprovementBuildableByFreshWater, stateForConditionals)
                    && tileInfo.isAdjacentTo(Constants.freshWater) -> true

            // I don't particularly like this check, but it is required to build mines on non-hill resources
            resourceIsVisible && tileInfo.tileResource.isImprovedBy(improvement.name) -> true
            // DEPRECATED since 4.0.14, REMOVE SOON:
            tileInfo.isLand && improvement.terrainsCanBeBuiltOn.isEmpty() && !improvement.hasUnique(
                UniqueType.CanOnlyImproveResource) -> true
            // No reason this improvement should be built here, so can't build it
            else -> false
        }
    }
}
