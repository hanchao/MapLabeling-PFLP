/*
Copyright Dietmar Ebner, 2004, ebner@apm.tuwien.ac.at

This file is part of PFLP.

PFLP is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

PFLP is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with PFLP; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/


package pflp.search;

import java.util.*;
import java.awt.geom.*;
import java.io.FileWriter;
import java.io.IOException;

import pflp.Label;
import pflp.PFLPApp;
import pflp.Solution;

/**
 * force directed labeling
 * @author Ebner Dietmar, ebner@apm.tuwien.ac.at
 */

public class ForceDirectedLabeling extends SearchThread
{
	public static final int X = 0;
	public static final int Y = 1;

	private static final double DEFAULT_FORCE_FAKT_REPULSIVE = 1.0;
	private static final double DEFAULT_FORCE_FAKT_OVERLAPPING = 10.0;
	private static final double DEFAULT_FORCE_FAKT_EPS = 0.5;
	private static final double DEFAULT_OVERLAPPING_PENALTY = 3 * (1 / (DEFAULT_FORCE_FAKT_EPS * DEFAULT_FORCE_FAKT_EPS));
	
	//enables some consistency checks...
	private static final boolean DEBUG = false;
	private static final boolean DEBUG_OUT = false;

	/**
	  * if the resulting force (absolute value) is smaller than this value it is 
	  * handled like it would be zero.
	  */
	public static final double MIN_FORCE = 0.5;

	private static final int SLIDE_HORIZONTAL = 1;
	private static final int SLIDE_VERTICAL = 2;

	private double temperature = 0;
	private double cooling_rate = 0;
	private int    moves_per_stage = 0;
	private int    size = 0;

	private long   nRejected = 0;
	private long   nTaken = 0;
	private long   nUnsignificant = 0;
	private long   nStages = 0;
	
	private long   nIterations = 0;
	
	private boolean simpleCleanup = false;
	
	private Label[] labels = null;
	private double[][] label_forces = null;
	private double[][][] label_label_forces = null;

	private HashSet obstructed = null;
	
	private double overallForce = 0.0;

	public ForceDirectedLabeling()
	{
		super();
		name = new String("force directed labeling");
	}

	public void enableSimpleCleanup()
	{
		simpleCleanup = true;
	}
	
	protected void precompute()
	{
		int i;

		label_forces = null;
		label_label_forces = null;
		obstructed = null;

		System.gc();

		//create initial solution
		PFLPApp.solution = new Solution(PFLPApp.instance, false);

		labels = PFLPApp.solution.getLabels();

		size = PFLPApp.solution.size();
		label_forces = new double[size][2];
		label_label_forces = new double[size][size][2];
		
		obstructed = new HashSet(size);

		overallForce = 0.0;
		for (i = 0; i < size; i++)
		{
			label_forces[i][X] = 0.0;
			label_forces[i][Y] = 0.0;

			for (int j = 0; j < size; j++)
			{
				label_label_forces[i][j][X] = 0.0;
				label_label_forces[i][j][Y] = 0.0;
			}
		}

		//find good start position
		for (i = 0; i < size; i++)
			toRandomPosition(i, false);

		//initialize forces...
		for (i = 0; i < size; i++)
			updateForce(i);
		
		//init temperature && initialize set of obstructed labels...
		double avg_lbl_size = 0;
		for (i = 0; i < size; i++)
		{
			if(!labels[i].getUnplacable() && (labels[i].isOverlapping() || canSlideHorizontal(labels[i]) || canSlideVertical(labels[i])))
				obstructed.add(labels[i]);
				
			avg_lbl_size += labels[i].getHeight() * labels[i].getWidth();
		}
		avg_lbl_size /= size;
		
		//we accept a overlap of p2 of the average label size with p1
		double p1 = 0.3; //propability of acceptance
		double p2 = 0.5; //percentage of overlap
		double eps_2 = DEFAULT_FORCE_FAKT_EPS * DEFAULT_FORCE_FAKT_EPS;
		temperature = avg_lbl_size * p2 * DEFAULT_FORCE_FAKT_OVERLAPPING + DEFAULT_OVERLAPPING_PENALTY + DEFAULT_FORCE_FAKT_REPULSIVE / eps_2;
		temperature /= -Math.log(p1);

		//temp. should become < 1 after N stages...	
		double N=15;	
		cooling_rate = Math.pow(1. / temperature, 1. / N);
		
		//moves per stage...
		moves_per_stage = 30 * size;
		
		if(DEBUG_OUT)
			System.out.println("starting annealing schedule: temperature " + Math.round(temperature) + ", cooling rate: " + cooling_rate + ", moves per stage: " + moves_per_stage + "!");

		nRejected = 0;
		nTaken = 0;
		nUnsignificant = 0;
		
		nIterations = 0;
		nStages = 0;
		
		if (DEBUG)
			testConsistency();
	}
	
	protected boolean iterate()
	{	
		nIterations ++;
		
		if(DEBUG)
			testConsistency();
	
		Label current = chooseNextCandidate();
		
		if(DEBUG && current != null && (!current.isOverlapping() && !canSlideHorizontal(current) && !canSlideVertical(current)))
			System.err.println("overlapping set boken...");
		
		//are there any movable or overlapping labels left?
		if(current == null)
		{
			if(DEBUG_OUT)
				System.out.println("break condition reached[1]: nothing left to do!");
			return true; 
		}
		
		//save some required label infos
		int    current_index = current.getIndex();
		double old_force = overallForce;
		double old_v_offset = current.getOffsetVertical();
		double old_h_offset = current.getOffsetHorizontal();

		//if the label want's to slide somewhere -> do it...
		boolean slideable_h = canSlideHorizontal(current);
		boolean slideable_v = canSlideVertical(current);
		
		boolean isUnsignificant = false;

		//boolean removeObstructed = false;
		if(slideable_h || slideable_v)
		{
			boolean slide_h = false;
			if(slideable_h && slideable_v) //flip a coin...
				slide_h = PFLPApp.random_generator.nextDouble() <= (Math.abs(label_forces[current_index][X]) / (Math.abs(label_forces[current_index][X]) + Math.abs(label_forces[current_index][Y])));
			else
				slide_h = slideable_h;
			
			findEquilibrium(current, slide_h);
			
			if((slide_h && canSlideHorizontal(current)) || (!slide_h && canSlideVertical(current)))
				toRandomPosition(current_index, true);
		}
		else //the label intersects at least one other label -> random position
		{
			toRandomPosition(current_index, true);
		}
		
		//take the move?
		double dE = overallForce - old_force;
		double p = PFLPApp.random_generator.nextDouble();

		if (dE > 0.0 && p > Math.exp(-dE / temperature))
		{
			//reject move
			moveLabel(current_index, old_h_offset, old_v_offset);
			nRejected ++;
		}
		else
		{
			//update set of obstructed labels....
			if(!current.isOverlapping() && !canSlideHorizontal(current) && !canSlideVertical(current))
				obstructed.remove(current);
			
			Iterator ni = current.getNeighbours().iterator();
			while (ni.hasNext())
			{
				Label ln = (Label) ni.next();
				if(ln.isOverlapping() || canSlideHorizontal(ln) || canSlideVertical(ln))
					obstructed.add(ln);
				else
					obstructed.remove(ln);
			}

			nTaken ++;
			if(Math.abs(dE) < MIN_FORCE)
				nUnsignificant ++;
		}
		
		if (nTaken + nRejected >= moves_per_stage)
		{
			int max_ovl = 0;
			Label candidate = null;
			
			Iterator d = obstructed.iterator();
			while(d.hasNext())
			{
				Label l = (Label) d.next();
				int n = 0;
				
				Iterator it = l.getNeighbours().iterator();
				while(it.hasNext())
				{
					Label l2 = (Label) it.next();
					if(!l2.getUnplacable() && l.doesIntersect(l2))
						n ++;
				}
				
				if(n > max_ovl)
				{
					max_ovl = n;
					candidate = l;
				}
			}
			
			if(candidate == null)
			{
				if(DEBUG_OUT)
					System.out.println("break condition reached[2]: no more overlapping labels!");
				return true; 
			}

			
			if(nTaken - nUnsignificant <= 0)
			{
				if(!PFLPApp.getOptionPointSelection())
					return true;
				
				if(simpleCleanup)
				{
					super.cleanupSolution(PFLPApp.solution);
					return true;
				}
				
				if (PFLPApp.gui != null)
					PFLPApp.gui.setStatusText("removed label: \"" + candidate.getNode().getText() + "\"...");

				removeLabel(candidate.getIndex());
			}
		
			//decrease temperature
			temperature = temperature * cooling_rate;

			//adjust moves_per_stage
			moves_per_stage = Math.max(size, Math.min(50 * obstructed.size(), 10 * size));

			nStages++;

			if(DEBUG_OUT)
				System.out.println("stage " + nStages + ": temperature: " +  temperature + ", nTaken = " + nTaken + "(" + (nTaken - nUnsignificant) + "), nRejected = " + nRejected + ", size = " + size + ", moves per stage (new): " + moves_per_stage);

			nRejected = 0;
			nTaken = 0;
			nUnsignificant = 0;
		}
		
		return false; //not yet ready...
	}
	
	/**
	 * moves the label to a randomly chosen position (4pos - model)
	 * forces on the label and/or neighbours are not updated!
	 * @param index the label to be moved...
	 */
	protected void toRandomPosition(int index, boolean updateForce)
	{
		double old_h = labels[index].getOffsetHorizontal();
		double old_v = labels[index].getOffsetVertical();
		
		do
		{
			int npos = PFLPApp.random_generator.nextInt(8);
			switch(npos)
			{
				case 0:
					labels[index].moveTo(Label.TOPLEFT);
					break;
				case 1:
					labels[index].moveTo(Label.TOPRIGHT);
					break;
				case 2:
					labels[index].moveTo(Label.BOTTOMLEFT);
					break;
				case 3:
					labels[index].moveTo(Label.BOTTOMRIGHT);
					break;
				case 4:
					labels[index].moveTo(labels[index].getWidth() / 2., labels[index].getHeight());
					break;
				case 5:
					labels[index].moveTo(0, labels[index].getHeight() / 2.);
					break;
				case 6:
					labels[index].moveTo(labels[index].getWidth() / 2., 0);
					break;
				case 7:
					labels[index].moveTo(labels[index].getWidth(), labels[index].getHeight() / 2.);
					break;
			}
		}while(old_h != labels[index].getOffsetHorizontal() && old_v == labels[index].getOffsetVertical());
		
		if(updateForce)
			updateForce(index);
	}

	/**
	 * returns a random label from the set of obstructed labels
	 * or null, if such a label does not exist
	 */
	protected Label chooseNextCandidate()
	{
		if(!obstructed.isEmpty())
		{
			int i = PFLPApp.random_generator.nextInt(obstructed.size());
			Iterator it = obstructed.iterator();
			Label l = null;
			for(int j=0; j<= i && it.hasNext(); j++)
				l = (Label)it.next();
		
			return l;
		}
		
		return null;
	}

	private boolean canSlideHorizontal(Label l)
	{
		return (
			(l.getOffsetVertical() == 0.0 || l.getOffsetVertical() == l.getHeight())
				&& Math.abs(label_forces[l.getIndex()][X]) >= MIN_FORCE
				&& ((label_forces[l.getIndex()][X] > 0.0 && l.getOffsetHorizontal() > 0)
					|| (label_forces[l.getIndex()][X] < 0.0 && l.getOffsetHorizontal() < l.getWidth())));

	}

	private boolean canSlideVertical(Label l)
	{
		return (
			(l.getOffsetHorizontal() == 0.0 || l.getOffsetHorizontal() == l.getWidth())
				&& Math.abs(label_forces[l.getIndex()][Y]) >= MIN_FORCE
				&& ((label_forces[l.getIndex()][Y] > 0.0 && l.getOffsetVertical() > 0.0)
					|| (label_forces[l.getIndex()][Y] < 0.0 && l.getOffsetVertical() < l.getHeight())));

	}

	private static int signum(double d)
	{
		return d >= 0. ? 1 : -1;
	}
	
	private void findEquilibrium(Label current, boolean slide_h)
	{
		int slide_dir = slide_h ? SLIDE_HORIZONTAL : SLIDE_VERTICAL;
		int i_current = current.getIndex();
		int searchIterations = 0;

		int old_direction = signum(slide_h ? label_forces[i_current][X] : label_forces[i_current][Y]);
		
		double total = 0;
		if(old_direction == 1)
			total = slide_h ? current.getOffsetHorizontal() : current.getOffsetVertical();
		else
			if(slide_h)
				total = current.getWidth() - current.getOffsetHorizontal();
			else
				total = current.getHeight() - current.getOffsetVertical();
		
		double amount = total * 0.2; //% of total (remaining) width/height

		double force_diff = 0;
		do
		{			
			if((slide_h && !canSlideHorizontal(current)) || (!slide_h && !canSlideVertical(current)))
			{
				if(DEBUG && searchIterations == 0)
					System.err.println("should be never reached [unable to slide label]...");

				return;
			}

			force_diff = slide_h ? label_forces[i_current][X] : label_forces[i_current][Y];

			slideBy(current, slide_dir, amount);
			
			force_diff = Math.abs(force_diff - (slide_h ? label_forces[i_current][X] : label_forces[i_current][Y]));
			
			int new_direction = signum(slide_h ? label_forces[i_current][X] : label_forces[i_current][Y]);
			if(old_direction != new_direction)
			{
				old_direction = new_direction;
				amount /= 2.;
			}
			
			searchIterations++;

		}while(searchIterations < 20 && Math.abs(slide_h ? label_forces[i_current][X] : label_forces[i_current][Y]) >= MIN_FORCE);
	}
	
	/**
	 * moves the label value units in the direction determined by 
	 * the first parameter
	 * be careful to update the force if required
	 * @param l the label to be moved	 
	 * @param direction horizontal or vertical moves?
	 * @param value determines amount of the change
	 */
	public void slideBy(Label l, int direction, double value)
	{
		double h_offset = l.getOffsetHorizontal();
		double v_offset = l.getOffsetVertical();
		int i_label = l.getIndex();

		value = Math.abs(value);

		if (direction == SLIDE_HORIZONTAL)
		{
			if (label_forces[i_label][X] > 0)
				h_offset = Math.max(0, l.getOffsetHorizontal() - value);
			else
				h_offset = Math.min(l.getWidth(), l.getOffsetHorizontal() + value);
		}
		else
		{
			if (label_forces[i_label][Y] > 0)
				v_offset = Math.max(0, l.getOffsetVertical() - value);
			else
				v_offset = Math.min(l.getHeight(), l.getOffsetVertical() + value);
		}

		moveLabel(i_label, h_offset, v_offset);
	}

	private void removeLabel(int i)
	{
		labels[i].setUnplacable(true);
		obstructed.remove(labels[i]);
		
		overallForce -= calcForceValue(label_forces[i]);

		label_forces[i][X] = label_forces[i][Y] = 0.0; //reset force

		//repair the forces when removing a label
		Iterator it = labels[i].getNeighbours().iterator();
		while (it.hasNext())
		{
			int j = ((Label) it.next()).getIndex();

			//updated force(s)
			overallForce -= calcForceValue(label_forces[j]);

			label_forces[j][X] -= label_label_forces[j][i][X];
			label_forces[j][Y] -= label_label_forces[j][i][Y];

			label_label_forces[i][j][X] = label_label_forces[j][i][X] = 0.0;
			label_label_forces[i][j][Y] = label_label_forces[j][i][Y] = 0.0;

			overallForce += calcForceValue(label_forces[j]);

			//is the neighour still obstructed?
			Label ln = labels[j];
			if(!ln.getUnplacable() && (ln.isOverlapping() || canSlideHorizontal(ln) || canSlideVertical(ln)))
				obstructed.add(ln);
			else
				obstructed.remove(ln);
		}
		return;
	}

	private void moveLabel(int i, double h_offset, double v_offset)
	{
		//		System.out.println(labels[i].getNode().getText() + ": " + 
		//			labels[i].getOffsetHorizontal()
		//				+ ", "
		//				+ labels[i].getOffsetVertical()
		//				+ " --> "
		//				+ h_offset
		//				+ ", "
		//				+ v_offset);

		labels[i].moveTo(h_offset, v_offset);
		updateForce(i);
	}

	private boolean existsOverlapping()
	{
		for(int k=0; k<size; k++)
		{
			if(!labels[k].getUnplacable() && labels[k].isOverlapping())
				return true;
		}
		
		return false;
	}

	/**
	 * Returns true, if the l1 intersects with the given
	 * label l2. This means, there is at least on point covered by
	 * both labels.
	 */
	public boolean doesIntersect(Label l1, Label l2)
	{
		Point2D.Double tl1 = l1.getTopleft();
		Point2D.Double tl2 = l2.getTopleft();

		return (
			(tl2.x + l2.getWidth() > tl1.x && tl2.x < tl1.x + l1.getWidth())
				&& (tl2.y + l2.getHeight() > tl1.y && tl2.y < tl1.y + l1.getHeight()));
	}

	private double calcForceValue(double[] f)
	{
		return Math.sqrt(f[X] * f[X] + f[Y] * f[Y]);
	}

	/**
	 * calculates the resultingforce vector according to the specific force model
	 */
	private void updateForce(int i)
	{
		if (labels[i].getUnplacable())
			return;

		overallForce -= calcForceValue(label_forces[i]);

		label_forces[i][X] = label_forces[i][Y] = 0.0; //reset force

		Iterator it = labels[i].getNeighbours().iterator();
		while (it.hasNext())
		{
			Label l2 = (Label) it.next();

			if (!l2.getUnplacable())
			{
				int j = l2.getIndex();
				double[] f_n = computeForce(i, j);

				label_label_forces[i][j][X] = f_n[X];
				label_label_forces[i][j][Y] = f_n[Y];

				label_forces[i][X] += f_n[X];
				label_forces[i][Y] += f_n[Y];

				//repair force vector at the current neighbour...
				overallForce -= calcForceValue(label_forces[j]);

				label_forces[j][X] -= label_label_forces[j][i][X];
				label_forces[j][Y] -= label_label_forces[j][i][Y];

				//force(i,j) = -force(j,i)
				label_label_forces[j][i][X] = -f_n[X];
				label_label_forces[j][i][Y] = -f_n[Y];

				label_forces[j][X] += label_label_forces[j][i][X];
				label_forces[j][Y] += label_label_forces[j][i][Y];

				overallForce += calcForceValue(label_forces[j]);
			}
		}

		overallForce += calcForceValue(label_forces[i]);
	}

	private double[] computeForce(int i_from, int i_to)
	{
		Label l1 = labels[i_from];
		Label l2 = labels[i_to];

		double K1 = DEFAULT_FORCE_FAKT_REPULSIVE;
		double K2 = DEFAULT_FORCE_FAKT_OVERLAPPING;
		double eps = DEFAULT_FORCE_FAKT_EPS;

		double f = 0.0;
		double l = l1.getDistance(l2);

		if (DEBUG)
		{
			if (labels[i_from].getUnplacable())
				System.err.println("computeForce() called for unplacable label (from)!");

			if (labels[i_to].getUnplacable())
				System.err.println("computeForce() called for unplacable label (to)!");
		}

		//the part proportional to the distance between two labels (small)
		double v = Math.max(l, eps);
		f = K1 / (v * v);

		//the part proportional to the intersection (if any) of the two labels
		if (l < 0)
		{
			Rectangle2D.Double r_int =
				(Rectangle2D.Double) l1.getRectangle().createIntersection((Rectangle2D) l2.getRectangle());
			if (!r_int.isEmpty())
			{
				double area = r_int.getHeight() * r_int.getWidth();
				f += K2 * area;
				f += DEFAULT_OVERLAPPING_PENALTY;
			}
		}

		Point2D.Double p1 = l1.getCenter();
		Point2D.Double p2 = l2.getCenter();
		double dx = p1.x - p2.x;
		double dy = p1.y - p2.y;
		double d = Math.sqrt(dx * dx + dy * dy);

		double[] f_ret = new double[2];
		if (d != 0.0)
		{
			f_ret[X] = f * dx / d;
			f_ret[Y] = f * dy / d;
		}
		return f_ret;
	}

	private void dumpForces(String filename)
	{
		try
		{
			FileWriter f = new FileWriter(filename);
			String out = new String("");
			for (int i = 0; i < labels.length; i++)
			{
				Label l = labels[i];
				f.write(
					i
						+ ": "
						+ l.getNode().getText()
						+ ", h_offset="
						+ Math.round(l.getOffsetHorizontal())
						+ ", v_offset="
						+ Math.round(l.getOffsetVertical())
						+ ", force[X]="
						+ Math.round(label_forces[i][X])
						+ ", force[Y]="
						+ Math.round(label_forces[i][Y])
						+ "\n");
			}

			f.close();
		}
		catch (IOException e)
		{
		}
	}

	public String getLabelInfo(int i)
	{
		if (i >= 0 && i < size && isActive())
		{
			String s = new String("");
			if (canSlideHorizontal(labels[i]))
			{
				s += label_forces[i][X] > 0 ? "R" : "L";
				s += "(" + ((double)Math.round(label_forces[i][X] * 10)) / 10 + ")";
			}

			if (canSlideVertical(labels[i]))
			{
				s += label_forces[i][Y] > 0 ? "D" : "U";
				s += "(" + ((double)Math.round(label_forces[i][Y] * 10)) / 10 + ")";
			}

			if(obstructed != null && obstructed.contains(labels[i]))
				s = "[*]" + s;
			
			return s;
		}
		else
		{
			return null;
		}
	}

	public String getStatusString()
	{
		if(isActive())
			return new String("temperature: " + Math.round(temperature*10) / 10. + ", overall force: " + Math.round(overallForce * 10) / 10. + ", stage: " + nStages);
		else
			return null;
	}
			
	/**
	 * reports data consitstency failures to stderr
	 */
	private void testConsistency()
	{
		testOverallForce();
		for (int i = 0; i < size; i++)
			testLabelForce(i);
	}

	private void testOverallForce()
	{
		double d = 0.0;

		for (int i = 0; i < size; i++)
		{
			d += calcForceValue(label_forces[i]);
		}

		if (Math.abs(d - overallForce) > 1e-3)
			System.err.println("testOverallForce() failed: " + d + " vs. " + overallForce);
	}

	private void testLabelForce(int i)
	{
		Iterator it = labels[i].getNeighbours().iterator();
		double x[] = new double[2];
		x[X] = 0.0;
		x[Y] = 0.0;

		if (!labels[i].getUnplacable())
		{
			while (it.hasNext())
			{
				Label l2 = (Label) it.next();

				if (l2.getUnplacable())
					continue;

				double[] f = computeForce(i, l2.getIndex());
				x[X] += f[X];
				x[Y] += f[Y];
			}
		}

		if (Math.abs(x[X] - label_forces[i][X]) > 1e-5 || Math.abs(x[Y] - label_forces[i][Y]) > 1e-5)
			System.err.println(
				"testLabelForce() failed: "
					+ labels[i].getNode().getText()
					+ ", ist=("
					+ label_forces[i][X]
					+ ", "
					+ label_forces[i][Y]
					+ "), soll=("
					+ x[X]
					+ ", "
					+ x[Y]
					+ ") + ");
	}

}
