package examples.StarterGhostComm;

import pacman.controllers.IndividualGhostController;
import pacman.game.Constants;
import pacman.game.Game;
import pacman.game.comms.BasicMessage;
import pacman.game.comms.Message;
import pacman.game.comms.Messenger;

import java.util.Random;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;


/**
 * Created by pwillic on 25/02/2016.
 */

public class MCTS_POCommGhost extends IndividualGhostController {
    private final static float CONSISTENCY = 0.9f;    //attack Ms Pac-Man with this probability
    private final static int PILL_PROXIMITY = 15;        //if Ms Pac-Man is this close to a power pill, back away
    Random rnd = new Random();
    private int TICK_THRESHOLD;
    private int lastPacmanIndex = -1;
    private int tickSeen = -1;

    // MCTS PARAMS
    private final static int NUM_SIMULATIONS = 100; // Adjust the number of simulations as needed

    public MCTS_POCommGhost(Constants.GHOST ghost) {
        this(ghost, 5);
    }

    public MCTS_POCommGhost(Constants.GHOST ghost, int TICK_THRESHOLD) {
        super(ghost);
        this.TICK_THRESHOLD = TICK_THRESHOLD;
    }

    @Override
    public Constants.MOVE getMove(Game game, long timeDue) {
        // Housekeeping - throw out old info
        int currentTick = game.getCurrentLevelTime();
        if (currentTick <= 2 || currentTick - tickSeen >= TICK_THRESHOLD) {
            lastPacmanIndex = -1;
            tickSeen = -1;
        }

        // Can we see PacMan? If so tell people and update our info
        int pacmanIndex = game.getPacmanCurrentNodeIndex();
        int currentIndex = game.getGhostCurrentNodeIndex(ghost);
        Messenger messenger = game.getMessenger();
        if (pacmanIndex != -1) {
            lastPacmanIndex = pacmanIndex;
            tickSeen = game.getCurrentLevelTime();
            if (messenger != null) {
                messenger.addMessage(new BasicMessage(ghost, null, BasicMessage.MessageType.PACMAN_SEEN, pacmanIndex, game.getCurrentLevelTime()));
            }
        }

        // Has anybody else seen PacMan if we haven't?
        if (pacmanIndex == -1 && game.getMessenger() != null) {
            for (Message message : messenger.getMessages(ghost)) {
                if (message.getType() == BasicMessage.MessageType.PACMAN_SEEN) {
                    if (message.getTick() > tickSeen && message.getTick() < currentTick) { // Only if it is newer information
                        lastPacmanIndex = message.getData();
                        tickSeen = message.getTick();
                    }
                }
            }
        }
        if (pacmanIndex == -1) {
            pacmanIndex = lastPacmanIndex;
        }

        Boolean requiresAction = game.doesGhostRequireAction(ghost);
        if (requiresAction != null && requiresAction)        //if ghost requires an action
        {
            if (pacmanIndex != -1) {
                if (game.getGhostEdibleTime(ghost) > 0 || closeToPower(game))    //retreat from Ms Pac-Man if edible or if Ms Pac-Man is close to power pill
                {
                    try {
                        return game.getApproximateNextMoveAwayFromTarget(game.getGhostCurrentNodeIndex(ghost),
                                game.getPacmanCurrentNodeIndex(), game.getGhostLastMoveMade(ghost), Constants.DM.PATH);
                    } catch (ArrayIndexOutOfBoundsException e) {
                        System.out.println(e);
                        System.out.println(pacmanIndex + " : " + currentIndex);
                    }
                } else {
                    try {
                        // Constants.MOVE move = game.getApproximateNextMoveTowardsTarget(game.getGhostCurrentNodeIndex(ghost),
                        //         pacmanIndex, game.getGhostLastMoveMade(ghost), Constants.DM.PATH);
                        // return move;
                        
                        // MCTS
                        return runMCTS(game);

                    } catch (ArrayIndexOutOfBoundsException e) {
                        System.out.println(e);
                        System.out.println(pacmanIndex + " : " + currentIndex);
                    }
                    
                }
            } else {
                Constants.MOVE[] possibleMoves = game.getPossibleMoves(game.getGhostCurrentNodeIndex(ghost), game.getGhostLastMoveMade(ghost));
                return possibleMoves[rnd.nextInt(possibleMoves.length)];
            }
        }
        return null;
    }

    private Constants.MOVE runMCTS(Game game) {
        Node root = new Node(null, null, 0, 0); // root node
        
        // Run simulations
        for (int i = 0; i< NUM_SIMULATIONS; i++) {
            // Selection, expansion, simulation, and backpropagration
            Node selectedNode = select(root);
            expand(selectedNode, game);
            int score = simulate(selectedNode, game);
            backpropagate(selectedNode, score);
        }

        // Choose the best move based on the most visited child
        Node bestChild = getBestChild(root);
        return bestChild == null ? Constants.MOVE.NEUTRAL : bestChild.move;

    }

    private Node select(Node root) {
        Node currentNode = root;

        while (!currentNode.children.isEmpty() && !isTerminal(currentNode)) {
            currentNode = getBestChild(currentNode);
        }

        return currentNode;
    }

    private void expand(Node node, Game game) {
        // Generate child nodes for legal moves from the current game state
        Constants.MOVE[] possibleMoves = game.getPossibleMoves(node.ghostPosition, node.move); //check


        for (Constants.MOVE move : possibleMoves) {
            int newGhostPosition = game.getNeighbour(node.ghostPosition, move);
            int newPacManPosition = game.getNeighbour(node.pacManPosition, move);


            Node child = new Node(node, move, newGhostPosition, newPacManPosition);
            node.children.add(child);
        }
    }

    private int simulate(Node node, Game game) {
        // Clone the game state to perform a simulation
        Game clonedGame = game.copy();

        int score = 0;
        int simulationDepth = 20;

        for (int i = 0; i < simulationDepth; i++) {
            // use random playouts on the cloned game state
            Constants.MOVE ghostMove = getRandomMove(game, node);
            Constants.MOVE pacManMove = getRandomMove(game, node);

            // Update the cloned game state
            clonedGame.advanceGame(pacManMove, new EnumMap<>(Map.of(ghost, ghostMove)));

            if (clonedGame.wasPacManEaten()) {
                score = Integer.MIN_VALUE;
                break;
            }
            else if (clonedGame.gameOver()) {
                score = Integer.MAX_VALUE;
                break;
            }
            else {
                score = clonedGame.getScore();
            }
        }

        return score;
    }

    private Constants.MOVE getRandomMove(Game game, Node node) {
        if (node == null || node.move == null) {
            return Constants.MOVE.NEUTRAL;
        }
        else {
            Constants.MOVE[] possibleMoves = game.getPossibleMoves(node.ghostPosition, node.move);
            return possibleMoves[rnd.nextInt(possibleMoves.length)];
        }
    }

    private static class Node {
        Node parent;
        Constants.MOVE move;
        int visits;
        int score;

        int ghostPosition;
        int pacManPosition;
        List<Node> children;

        public Node(Node parent, Constants.MOVE move, int ghostPosition, int pacManPosition) {
            this.parent = parent;
            this.move = move;
            this.visits = 0;
            this.score = 0;
            this.ghostPosition = ghostPosition;
            this.pacManPosition = pacManPosition;
            this.children = new ArrayList<>();
        }

    }

    //This helper function checks if Ms Pac-Man is close to an available power pill
    private boolean closeToPower(Game game) {
        int[] powerPills = game.getPowerPillIndices();

        for (int i = 0; i < powerPills.length; i++) {
            Boolean powerPillStillAvailable = game.isPowerPillStillAvailable(i);
            int pacmanNodeIndex = game.getPacmanCurrentNodeIndex();
            if (pacmanNodeIndex == -1) {
                pacmanNodeIndex = lastPacmanIndex;
            }
            if (powerPillStillAvailable == null || pacmanNodeIndex == -1) {
                return false;
            }
            if (powerPillStillAvailable && game.getShortestPathDistance(powerPills[i], pacmanNodeIndex) < PILL_PROXIMITY) {
                return true;
            }
        }

        return false;
    }
}