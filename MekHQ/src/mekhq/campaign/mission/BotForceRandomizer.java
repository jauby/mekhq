package mekhq.campaign.mission;

import megamek.Version;
import megamek.client.generator.RandomGenderGenerator;
import megamek.client.generator.RandomNameGenerator;
import megamek.client.generator.enums.SkillGeneratorType;
import megamek.client.generator.skillGenerators.AbstractSkillGenerator;
import megamek.client.generator.skillGenerators.TaharqaSkillGenerator;
import megamek.common.*;
import megamek.common.annotations.Nullable;
import megamek.common.enums.Gender;
import megamek.common.enums.SkillLevel;
import megamek.common.util.StringUtil;
import mekhq.MekHqXmlSerializable;
import mekhq.MekHqXmlUtil;
import mekhq.campaign.Campaign;
import mekhq.campaign.mission.atb.AtBScenarioFactory;
import mekhq.campaign.mission.atb.IAtBScenario;
import mekhq.campaign.mission.enums.ScenarioStatus;
import mekhq.campaign.personnel.Bloodname;
import mekhq.campaign.personnel.enums.Phenotype;
import mekhq.campaign.unit.Unit;
import mekhq.campaign.universe.Faction;
import mekhq.campaign.universe.Factions;
import mekhq.campaign.universe.UnitGeneratorParameters;
import org.apache.logging.log4j.LogManager;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.PrintWriter;
import java.io.Serializable;
import java.util.*;

/**
 * A class that can be used to generate a random force with some parameters. Provides a simpler approach
 * to opfor generation than AtBDynamicScenarioFactory. Intended for use by StoryArc but written generally
 * enough to be repurposed.
 *
 * Unlike AtBDynamicScenarioFactory, the methods here are not static, but depend on variables in an actual
 * BotForceRandomizer than can be added to a BotForce. If present, this randomizer will be used to generate
 * forces for the BotForce.
 */
public class BotForceRandomizer implements Serializable, MekHqXmlSerializable {

    private enum BalancingMethod {
        BV,
        WEIGHT;
    }

    /** faction to draw from **/
    private String factionCode;

    /** skill level **/
    private SkillLevel skill;

    /** unit quality level **/
    private int quality;

    /** unit type **/
    private int unitType;

    /** force multiplier relative to player's deployed forces **/
    private double forceMultiplier;

    /** balancing method **/
    private BalancingMethod balancingMethod;

    /** convenience campaign pointer **/
    private Campaign campaign;

    /**
     * what percent of mek and aero forces should actually be conventional?
     * (tanks and conventional aircraft respectively)
     **/
    private int percentConventional;

    public BotForceRandomizer() {
        skill = SkillLevel.REGULAR;
        unitType = UnitType.MEK;
        forceMultiplier = 1.0;
        percentConventional = 0;
    }

    public List<Entity> generateForce(List<Unit> playerUnits, List<Entity> botFixedEntities) {
        ArrayList<Entity> entityList = new ArrayList<>();

        int maxPoints = calculateMaxPoints(playerUnits);
        int currentPoints = calculateStartingPoints(botFixedEntities);

        while(currentPoints < maxPoints) {
            //TODO: do a bunch of generation
            //one of the big issues will be how to select weight classes. I think this should be targeted to the
            //player's deployed forces average weight. Use some kind of probabilistic function to keep the average
            //close to the players

            //Should i keep things in lances or just generate individual entities until I go over the point limit
            //my preference is probably the latter

            // for testing
            Entity e = getEntity(UnitType.MEK, EntityWeightClass.WEIGHT_MEDIUM);
            if(null != e) {
                entityList.add(e);
                currentPoints += e.calculateBattleValue();
            }
        }

        return entityList;
    }


    /**
     * Determines the most appropriate RAT and uses it to generate a random Entity
     *
     * @param uType    The UnitTableData constant for the type of unit to generate.
     * @param weightClass The weight class of the unit to generate
     * @return A new Entity with crew.
     */
    public Entity getEntity(int uType, int weightClass) {
        MechSummary ms;

        UnitGeneratorParameters params = new UnitGeneratorParameters();
        params.setFaction(factionCode);
        params.setQuality(quality);
        params.setUnitType(uType);
        params.setWeightClass(weightClass);
        params.setYear(campaign.getGameYear());

        /*
        if (unitType == UnitType.TANK) {
            return getTankEntity(params, skill, campaign);
        } else if (unitType == UnitType.INFANTRY) {
            return getInfantryEntity(params, skill, campaign);
        } else {
            ms = campaign.getUnitGenerator().generate(params);
        }

        if (ms == null) {
            return null;
        }
        */

        // for testing
        ms = campaign.getUnitGenerator().generate(params);

        return createEntityWithCrew(ms);
    }

    /**
     * @param ms Which entity to generate
     * @return A crewed entity
     */
    public @Nullable Entity createEntityWithCrew(MechSummary ms) {
        Entity en;
        try {
            en = new MechFileParser(ms.getSourceFile(), ms.getEntryName()).getEntity();
        } catch (Exception ex) {
            LogManager.getLogger().error("Unable to load entity: " + ms.getSourceFile() + ": " + ms.getEntryName(), ex);
            return null;
        }
        Faction faction = Factions.getInstance().getFaction(factionCode);

        en.setOwner(campaign.getPlayer());
        en.setGame(campaign.getGame());

        RandomNameGenerator rng = RandomNameGenerator.getInstance();
        rng.setChosenFaction(faction.getNameGenerator());
        Gender gender = RandomGenderGenerator.generate();
        String[] crewNameArray = rng.generateGivenNameSurnameSplit(gender, faction.isClan(), faction.getShortName());
        String crewName = crewNameArray[0];
        crewName += !StringUtil.isNullOrEmpty(crewNameArray[1]) ?  " " + crewNameArray[1] : "";

        Map<Integer, Map<String, String>> extraData = new HashMap<>();
        Map<String, String> innerMap = new HashMap<>();
        innerMap.put(Crew.MAP_GIVEN_NAME, crewNameArray[0]);
        innerMap.put(Crew.MAP_SURNAME, crewNameArray[1]);

        final AbstractSkillGenerator skillGenerator = new TaharqaSkillGenerator();
        skillGenerator.setLevel(skill);
        if (faction.isClan()) {
            skillGenerator.setType(SkillGeneratorType.CLAN);
        }
        int[] skills = skillGenerator.generateRandomSkills(en);

        if (faction.isClan() && (Compute.d6(2) > (6 - skill.ordinal() + skills[0] + skills[1]))) {
            Phenotype phenotype = Phenotype.NONE;
            switch (en.getUnitType()) {
                case UnitType.MEK:
                    phenotype = Phenotype.MECHWARRIOR;
                    break;
                case UnitType.TANK:
                case UnitType.VTOL:
                    // The Vehicle Phenotype is unique to Clan Hell's Horses
                    if (faction.getShortName().equals("CHH")) {
                        phenotype = Phenotype.VEHICLE;
                    }
                    break;
                case UnitType.BATTLE_ARMOR:
                    phenotype = Phenotype.ELEMENTAL;
                    break;
                case UnitType.AERO:
                case UnitType.CONV_FIGHTER:
                    phenotype = Phenotype.AEROSPACE;
                    break;
                case UnitType.PROTOMEK:
                    phenotype = Phenotype.PROTOMECH;
                    break;
                case UnitType.SMALL_CRAFT:
                case UnitType.DROPSHIP:
                case UnitType.JUMPSHIP:
                case UnitType.WARSHIP:
                    // The Naval Phenotype is unique to Clan Snow Raven and the Raven Alliance
                    if (faction.getShortName().equals("CSR") || faction.getShortName().equals("RA")) {
                        phenotype = Phenotype.NAVAL;
                    }
                    break;
            }

            if (phenotype != Phenotype.NONE) {
                String bloodname = Bloodname.randomBloodname(faction.getShortName(), phenotype,
                        campaign.getGameYear()).getName();
                crewName += " " + bloodname;
                innerMap.put(Crew.MAP_BLOODNAME, bloodname);
                innerMap.put(Crew.MAP_PHENOTYPE, phenotype.name());
            }
        }

        extraData.put(0, innerMap);

        en.setCrew(new Crew(en.getCrew().getCrewType(), crewName, Compute.getFullCrewSize(en),
                skills[0], skills[1], gender, extraData));

        en.setExternalIdAsString(UUID.randomUUID().toString());
        return en;
    }

    private int calculateMaxPoints(List<Unit> playerUnits) {
        int maxPoints = 0;
        for(Unit u : playerUnits) {
            maxPoints += u.getEntity().calculateBattleValue();
            //TODO: allow other method of point generation
        }

        maxPoints = (int) Math.ceil(maxPoints * forceMultiplier);
        return maxPoints;
    }

    private int calculateStartingPoints(List<Entity> botEntities) {
        int startPoints = 0;
        for(Entity e : botEntities) {
            startPoints += e.calculateBattleValue();
            //TODO: allow other method of point generation
        }

        return startPoints;
    }

    @Override
    public void writeToXml(PrintWriter pw1, int indent) {
        pw1.println(MekHqXmlUtil.indentStr(indent) + "<botForceRandomizer>");
        pw1.println(MekHqXmlUtil.indentStr(indent+1)
                +"<factionCode>"
                +factionCode
                +"</factionCode>");
        pw1.println(MekHqXmlUtil.indentStr(indent+1)
                +"<quality>"
                +quality
                +"</quality>");
        pw1.println(MekHqXmlUtil.indentStr(indent+1)
                +"<skill>"
                +skill.name()
                +"</skill>");
        pw1.println(MekHqXmlUtil.indentStr(indent+1)
                +"<unitType>"
                +unitType
                +"</unitType>");
        pw1.println(MekHqXmlUtil.indentStr(indent+1)
                +"<forceMultiplier>"
                +forceMultiplier
                +"</forceMultiplier>");
        pw1.println(MekHqXmlUtil.indentStr(indent+1)
                +"<balancingMethod>"
                +balancingMethod.name()
                +"</balancingMethod>");
        pw1.println(MekHqXmlUtil.indentStr(indent+1)
                +"<percentConventional>"
                +percentConventional
                +"</percentConventional>");
        pw1.println(MekHqXmlUtil.indentStr(indent) + "</botForceRandomizer>");
    }

    public static BotForceRandomizer generateInstanceFromXML(Node wn, Campaign c, Version version) {
        BotForceRandomizer retVal = new BotForceRandomizer();

        retVal.campaign = c;
        try {
            // Okay, now load Part-specific fields!
            NodeList nl = wn.getChildNodes();

            for (int x = 0; x < nl.getLength(); x++) {
                Node wn2 = nl.item(x);

                if (wn2.getNodeName().equalsIgnoreCase("factionCode")) {
                    retVal.factionCode = wn2.getTextContent().trim();
                } else if (wn2.getNodeName().equalsIgnoreCase("quality")) {
                    retVal.quality = Integer.parseInt(wn2.getTextContent().trim());
                } else if (wn2.getNodeName().equalsIgnoreCase("unitType")) {
                    retVal.unitType = Integer.parseInt(wn2.getTextContent().trim());
                } else if (wn2.getNodeName().equalsIgnoreCase("skill")) {
                    retVal.skill = SkillLevel.valueOf(wn2.getTextContent().trim());
                } else if (wn2.getNodeName().equalsIgnoreCase("forceMultiplier")) {
                    retVal.forceMultiplier = Double.parseDouble(wn2.getTextContent());
                } else if (wn2.getNodeName().equalsIgnoreCase("percentConventional")) {
                    retVal.percentConventional = Integer.parseInt(wn2.getTextContent().trim());
                } else if (wn2.getNodeName().equalsIgnoreCase("balancingMethod")) {
                    retVal.balancingMethod = BalancingMethod.valueOf(wn2.getTextContent().trim());
                }
            }
        }  catch (Exception ex) {
            LogManager.getLogger().error(ex);
        }

        return retVal;
    }

}
